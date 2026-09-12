package com.example.uhf.utils;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🏗️ MiraExecutorService - مدير الخيوط المشترك على مستوى التطبيق
 *
 * ⚠️ هذا الـ Singleton يعيش مع التطبيق كاملاً.
 * لا يُغلق أبداً خلال حياة العملية.
 *
 * ✅ مُصحَّح: بدون setDaemon(true)
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

    /**
     * ✅ فحص سلامة المدير
     */
    public boolean isHealthy() {
        try {
            return !singleThreadExecutor.isShutdown()
                    && !networkExecutor.isShutdown()
                    && !backgroundExecutor.isShutdown()
                    && !imageExecutor.isShutdown();
        } catch (Exception e) {
            return false;
        }
    }

    public static MiraExecutorService getInstance() {
        if (INSTANCE == null) {
            synchronized (MiraExecutorService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MiraExecutorService();
                }
            }
        }
        return INSTANCE;
    }

    public ExecutorService getSingleThreadExecutor() {
        return singleThreadExecutor;
    }

    public ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }

    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    public ExecutorService getImageExecutor() {
        return imageExecutor;
    }

    /**
     * ⚠️ للاستخدام الطارئ فقط — عادةً لا يُستدعى
     */
    public void shutdownAll() {
        Log.w(TAG, "⚠️ shutdownAll called");
        try {
            singleThreadExecutor.shutdownNow();
            networkExecutor.shutdownNow();
            backgroundExecutor.shutdownNow();
            imageExecutor.shutdownNow();
        } catch (Exception e) {
            Log.e(TAG, "shutdownAll error", e);
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            // ✅ تم حذف t.setDaemon(true) — كان يُسبب موت الخيوط
            // ✅ الآن الخيوط تبقى حية مع التطبيق
            return t;
        }
    }
}
