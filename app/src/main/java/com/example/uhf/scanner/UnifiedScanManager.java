package com.example.uhf.scanner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ المدير الموحّد لعمليات المسح الثلاث
 * - RFID
 * - GS1 (CameraX)
 * - Manual
 */
public class UnifiedScanManager {
    
    private static final String TAG = "UnifiedScanManager";
    
    // ==================== Callbacks ====================
    
    public interface ScanCallback {
        void onScanResult(ScanResult result);
        void onSessionStarted(int expectedCount);
        void onSessionError(String error);
        void onSessionCompleted(int scannedCount);
        void onSessionCancelled();
    }
    
    // ==================== State ====================
    
    private final Context context;
    private final MiraApiClient apiClient;
    private final Handler mainHandler;
    private final ExecutorService executor;
    
    private int currentTransferId = 0;
    private String currentPhase = "";
    private ScanMethod currentMethod = ScanMethod.RFID;
    private ScanCallback currentCallback = null;
    private boolean sessionActive = false;
    
    // منع المسح المكرر
    private final Set<String> scannedIdentifiers = new HashSet<>();
    
    // ==================== Singleton ====================
    
    private static volatile UnifiedScanManager instance;
    
    public static UnifiedScanManager getInstance(Context context) {
        if (instance == null) {
            synchronized (UnifiedScanManager.class) {
                if (instance == null) {
                    instance = new UnifiedScanManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }
    
    private UnifiedScanManager(Context context) {
        this.context = context;
        this.apiClient = MiraApiClient.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    // ==================== Session Management ====================
    
    /**
     * ✅ بدء جلسة مسح جديدة
     * 
     * @param transferId معرف الشحنة
     * @param phase "dispatch" أو "receive"
     * @param method طريقة المسح الافتراضية
     * @param callback callback النتائج
     */
    public void startSession(int transferId, String phase, ScanMethod method, ScanCallback callback) {
        if (sessionActive) {
            Log.w(TAG, "Session already active. Cancel first.");
            if (callback != null) {
                callback.onSessionError("جلسة مسح نشطة بالفعل");
            }
            return;
        }
        
        this.currentTransferId = transferId;
        this.currentPhase = phase;
        this.currentMethod = method;
        this.currentCallback = callback;
        this.scannedIdentifiers.clear();
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("phase", phase);
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth(
                    "/transfers/" + transferId + "/scan-session/start", payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        sessionActive = true;
                        int expectedCount = response.data.optInt("expected_count", 0);
                        
                        Log.d(TAG, "✅ Session started. Expected: " + expectedCount);
                        
                        if (currentCallback != null) {
                            currentCallback.onSessionStarted(expectedCount);
                        }
                    } else {
                        String err = response != null ? response.message : "خطأ غير معروف";
                        Log.e(TAG, "Session start failed: " + err);
                        if (currentCallback != null) {
                            currentCallback.onSessionError(err);
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "startSession error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (currentCallback != null) {
                        currentCallback.onSessionError(e.getMessage());
                    }
                });
            }
        });
    }
    
    /**
     * ✅ مسح قطعة (يُستدعى من RFID أو الكاميرا أو الإدخال اليدوي)
     * 
     * @param method الطريقة المستخدمة
     * @param identifier القيمة (EPC للـ RFID، serial للـ GS1، أي شيء للـ manual)
     * @param gtin اختياري (للـ GS1)
     */
    public void scanItem(ScanMethod method, String identifier, String gtin) {
        if (!sessionActive) {
            Log.w(TAG, "No active session");
            if (currentCallback != null) {
                currentCallback.onScanResult(
                    ScanResult.error("لا توجد جلسة مسح نشطة", method.getCode()));
            }
            return;
        }
        
        if (identifier == null || identifier.trim().isEmpty()) {
            Log.w(TAG, "Empty identifier");
            return;
        }
        
        String cleanId = identifier.trim().toUpperCase();
        
        // ✅ تجاهل المكرر
        String cacheKey = method.getCode() + ":" + cleanId;
        if (scannedIdentifiers.contains(cacheKey)) {
            Log.d(TAG, "⚠️ Already scanned locally: " + cleanId);
            return;
        }
        
        scannedIdentifiers.add(cacheKey);
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("phase", currentPhase);
                payload.put("method", method.getCode());
                
                switch (method) {
                    case RFID:
                        payload.put("epc", cleanId);
                        break;
                    case GS1:
                        payload.put("serial", cleanId);
                        if (gtin != null && !gtin.isEmpty()) {
                            payload.put("gtin", gtin);
                        }
                        break;
                    case MANUAL:
                        payload.put("identifier", cleanId);
                        break;
                }
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth(
                    "/transfers/" + currentTransferId + "/scan-item", payload);
                
                mainHandler.post(() -> {
                    ScanResult result;
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        result = ScanResult.fromJson(response.data, method.getCode());
                    } else if (response != null) {
                        result = ScanResult.error(
                            response.message != null ? response.message : "خطأ في السيرفر",
                            method.getCode());
                    } else {
                        result = ScanResult.error("لا يوجد استجابة", method.getCode());
                    }
                    
                    if (currentCallback != null) {
                        currentCallback.onScanResult(result);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "scanItem error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (currentCallback != null) {
                        currentCallback.onScanResult(
                            ScanResult.error(e.getMessage(), method.getCode()));
                    }
                });
            }
        });
    }
    
    /**
     * ✅ إتمام الجلسة
     * - dispatch → complete-dispatch
     * - receive → complete-receive
     */
    public void completeSession() {
        if (!sessionActive) {
            Log.w(TAG, "No active session to complete");
            return;
        }
        
        final String phase = currentPhase;
        final int transferId = currentTransferId;
        
        executor.execute(() -> {
            try {
                String endpoint = "dispatch".equals(phase)
                    ? "/transfers/" + transferId + "/complete-dispatch"
                    : "/transfers/" + transferId + "/complete-receive";
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth(
                    endpoint, new JSONObject());
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        int scannedCount = response.data.optInt("scanned_count", 0);
                        
                        Log.d(TAG, "✅ Session completed. Scanned: " + scannedCount);
                        
                        sessionActive = false;
                        
                        if (currentCallback != null) {
                            currentCallback.onSessionCompleted(scannedCount);
                        }
                    } else {
                        String err = response != null ? response.message : "خطأ غير معروف";
                        
                        // هل يظهر خطأ القطع الناقصة؟
                        if (response != null && response.data != null) {
                            int missing = response.data.optInt("missing_count", 0);
                            if (missing > 0) {
                                err = "يوجد " + missing + " قطعة لم تُمسح بعد";
                            }
                        }
                        
                        Log.e(TAG, "Complete failed: " + err);
                        
                        if (currentCallback != null) {
                            currentCallback.onSessionError(err);
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "completeSession error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (currentCallback != null) {
                        currentCallback.onSessionError(e.getMessage());
                    }
                });
            }
        });
    }
    
    /**
     * ✅ إلغاء الجلسة (بدون إتمام)
     */
    public void cancelSession() {
    if (!sessionActive) return;
    
    final int transferId = currentTransferId;
    final String phase = currentPhase;
    
    executor.execute(() -> {
        try {
            // ✅ استخدم DELETE مع ApiClient
            // ملاحظة: MiraApiClient لا يوفر DELETE حالياً — استخدم updateTransfer بقيمة status
            // أو استخدم executeRequest إذا كانت public
            
            // ✅ الحل المؤقت: استخدم HTTP مباشرة أو تجاهل الخطأ
            // الأفضل: استخدام getWithAuth في endpoint مخصص
            
            // ✅ الحل الآن: نُنشئ Endpoint يستخدم POST بدلاً من DELETE
            MiraApiClient.ApiResponse response = apiClient.postWithAuth(
                "/transfers/" + transferId + "/scan-session/cancel",
                new JSONObject()
            );
            
            Log.d(TAG, "Session cancelled: " + (response != null ? response.code : "no response"));
            
        } catch (Exception e) {
            Log.e(TAG, "cancelSession error: " + e.getMessage());
        }
        
        mainHandler.post(() -> {
            sessionActive = false;
            scannedIdentifiers.clear();
            
            if (currentCallback != null) {
                currentCallback.onSessionCancelled();
            }
        });
    });
}
    
    /**
     * ✅ جلب حالة المسح الحالية من السيرفر
     */
    public void fetchScanStatus(ScanCallback callback) {
        if (currentTransferId <= 0) {
            if (callback != null) {
                callback.onSessionError("لا توجد شحنة محددة");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                String endpoint = "/transfers/" + currentTransferId
                    + "/scan-status?phase=" + currentPhase;
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        int scanned = response.data.optInt("scanned_count", 0);
                        int total = response.data.optInt("total", 0);
                        
                        Log.d(TAG, "Status: " + scanned + "/" + total);
                        
                        if (callback != null) {
                            callback.onSessionStarted(total);
                        }
                    } else {
                        String err = response != null ? response.message : "خطأ";
                        if (callback != null) {
                            callback.onSessionError(err);
                        }
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "fetchScanStatus error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSessionError(e.getMessage());
                    }
                });
            }
        });
    }
    
    // ==================== Getters ====================
    
    public boolean isSessionActive() { return sessionActive; }
    public int getCurrentTransferId() { return currentTransferId; }
    public String getCurrentPhase() { return currentPhase; }
    public ScanMethod getCurrentMethod() { return currentMethod; }
    public void setCurrentMethod(ScanMethod method) { this.currentMethod = method; }
    
    // ==================== Cleanup ====================
    
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        sessionActive = false;
        scannedIdentifiers.clear();
    }
}
