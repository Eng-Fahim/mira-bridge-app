package com.example.uhf.utils;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiraExecutorService - الإصدار 2.0 (مدير الخيوط المشترك)
 *
 * ⚠️ هذا الـ Singleton يعيش مع التطبيق كاملاً.
 * لا يُغلق أبداً خلال حياة العملية (إلا في onTerminate).
 *
 * ✅ مزايا:
 * - 4 executors متخصصة (single/network/background/image)
 * - ThreadFactory مع أسماء
 * - isHealthy() لفحص الحالة
 * - shutdown() + shutdownAll() للإغلاق
 * - getStats() للإحصائيات
 * - tryGetInstance() للفحص الآمن
 *
 * ⚠️ ملاحظة: بدون setDaemon(true)
 * — لأن Android يقتل daemon threads عند أول فرصة
 * — مما يُسبب تجمّد التطبيق بعد config changes
 */
public class MiraExecutorService {

    private static final String TAG = "MiraExecutorService";

    private static volatile MiraExecutorService INSTANCE;

    private final ExecutorService singleThreadExecutor;
    private final ExecutorService networkExecutor;
    private final ExecutorService backgroundExecutor;
    private final ExecutorService imageExecutor;

    private volatile boolean isShutdown = false;

    // ==================== Constructor ====================

    private MiraExecutorService() {
        Log.i(TAG, "🏗️ Initializing MiraExecutorService...");

        this.singleThreadExecutor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("Mira-Single"));

        this.networkExecutor = Executors.newFixedThreadPool(4,
                new NamedThreadFactory("Mira-Network"));

        this.backgroundExecutor = Executors.newFixedThreadPool(3,
                new NamedThreadFactory("Mira-Background"));

        this.imageExecutor = Executors.newFixedThreadPool(2,
                new NamedThreadFactory("Mira-Image"));

        Log.i(TAG, "✅ MiraExecutorService initialized");
    }

    // ==================== Singleton ====================

    /**
     * ✅ يرمي استثناء إذا فشل (بعد shutdown)
     */
    @NonNull
    public static MiraExecutorService getInstance() {
        MiraExecutorService local = INSTANCE;
        if (local == null) {
            synchronized (MiraExecutorService.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new MiraExecutorService();
                    INSTANCE = local;
                }
            }
        }

        // ⚠️ إذا كان المُغلَق موجود، أنشئ جديد
        if (local.isShutdown()) {
            synchronized (MiraExecutorService.class) {
                if (INSTANCE == null || INSTANCE.isShutdown()) {
                    INSTANCE = new MiraExecutorService();
                }
                local = INSTANCE;
            }
        }

        return local;
    }

    /**
     * ✅ نسخة آمنة — تُعيد null إذا فشل
     */
    public static MiraExecutorService tryGetInstance() {
        try {
            return getInstance();
        } catch (Throwable t) {
            Log.e(TAG, "tryGetInstance failed", t);
            return null;
        }
    }

    // ==================== Getters ====================

    @NonNull
    public ExecutorService getSingleThreadExecutor() {
        return singleThreadExecutor;
    }

    @NonNull
    public ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }

    @NonNull
    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    @NonNull
    public ExecutorService getImageExecutor() {
        return imageExecutor;
    }

    // ==================== Health ====================

    /**
     * ✅ فحص سلامة المدير
     */
    public boolean isHealthy() {
        try {
            return !isShutdown
                    && !singleThreadExecutor.isShutdown()
                    && !networkExecutor.isShutdown()
                    && !backgroundExecutor.isShutdown()
                    && !imageExecutor.isShutdown();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * ✅ إحصائيات (للتشخيص)
     */
    @NonNull
    public String getStats() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🏗️ MiraExecutorService Stats\n");
            sb.append("─────────────\n");
            sb.append("Shutdown: ").append(isShutdown).append("\n");
            sb.append("Single: ").append(singleThreadExecutor.isShutdown() ? "CLOSED" : "OPEN").append("\n");
            sb.append("Network: ").append(networkExecutor.isShutdown() ? "CLOSED" : "OPEN").append("\n");
            sb.append("Background: ").append(backgroundExecutor.isShutdown() ? "CLOSED" : "OPEN").append("\n");
            sb.append("Image: ").append(imageExecutor.isShutdown() ? "CLOSED" : "OPEN").append("\n");
            return sb.toString();
        } catch (Throwable t) {
            return "Stats error: " + t.getMessage();
        }
    }

    // ==================== Shutdown ====================

    /**
     * ✅ [إضافة] alias لـ shutdownAll() — يستخدمه MiraApplication
     */
    public void shutdown() {
        shutdownAll();
    }

    /**
     * ⚠️ للاستخدام الطارئ فقط — عادةً لا يُستدعى
     * يُستدعى من MiraApplication.onTerminate()
     */
    public synchronized void shutdownAll() {
        if (isShutdown) {
            Log.d(TAG, "shutdownAll: already shutdown");
            return;
        }

        Log.w(TAG, "⚠️ shutdownAll called — closing all executors");
        isShutdown = true;

        try {
            singleThreadExecutor.shutdownNow();
        } catch (Throwable t) {
            Log.e(TAG, "singleThreadExecutor shutdown error", t);
        }

        try {
            networkExecutor.shutdownNow();
        } catch (Throwable t) {
            Log.e(TAG, "networkExecutor shutdown error", t);
        }

        try {
            backgroundExecutor.shutdownNow();
        } catch (Throwable t) {
            Log.e(TAG, "backgroundExecutor shutdown error", t);
        }

        try {
            imageExecutor.shutdownNow();
        } catch (Throwable t) {
            Log.e(TAG, "imageExecutor shutdown error", t);
        }

        Log.i(TAG, "✅ MiraExecutorService shutdown complete");
    }

    // ==================== ThreadFactory ====================

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            // ✅ تم حذف t.setDaemon(true) — كان يُسبب موت الخيوط
            // ✅ الآن الخيوط تبقى حية مع التطبيق
            // ✅ لكن بأولوية عادية (NORM_PRIORITY)
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
