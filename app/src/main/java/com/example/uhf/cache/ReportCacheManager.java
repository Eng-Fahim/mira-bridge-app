package com.example.uhf.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.LruCache;

import com.example.uhf.model.ReportModels;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * ✅ مدير التخزين المؤقت متعدد المستويات
 * المستوى 1: ذاكرة LRU
 * المستوى 2: SharedPreferences (للبيانات الصغيرة)
 * المستوى 3: القرص (للبيانات الكبيرة)
 */
public class ReportCacheManager {
    private static final String TAG = "ReportCacheManager";
    private static final String PREFS_NAME = "report_cache";
    private static final long SMALL_DATA_THRESHOLD = 10 * 1024; // 10KB
    
    private final SharedPreferences prefs;
    private final File diskCacheDir;
    private final LruCache<String, ReportModels.ReportResult> memoryCache;
    
    public ReportCacheManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.diskCacheDir = new File(context.getCacheDir(), "reports_cache");
        
        // تهيئة LRU Cache
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        
        this.memoryCache = new LruCache<String, ReportModels.ReportResult>(cacheSize) {
            @Override
            protected int sizeOf(String key, ReportModels.ReportResult result) {
                if (result.data != null) {
                    return result.data.toString().getBytes().length / 1024;
                }
                return 1;
            }
        };
        
        // إنشاء مجلد الكاش
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
    }
    
    /**
     * ✅ حفظ تقرير في الكاش
     */
    public void cacheReport(String key, JSONObject data) {
        try {
            ReportModels.ReportResult result = ReportModels.ReportResult.success(data);
            
            // 1. حفظ في ذاكرة LRU
            memoryCache.put(key, result);
            
            String dataString = data.toString();
            
            // 2. حفظ في SharedPreferences إذا كانت البيانات صغيرة
            if (dataString.length() < SMALL_DATA_THRESHOLD) {
                prefs.edit().putString(key, dataString).apply();
            } 
            // 3. حفظ في القرص إذا كانت البيانات كبيرة
            else {
                saveToDisk(key, dataString);
            }
            
            Log.d(TAG, "Cached report: " + key);
            
        } catch (Exception e) {
            Log.e(TAG, "Error caching report: " + e.getMessage());
        }
    }
    
    /**
     * ✅ استرجاع تقرير من الكاش
     */
    public ReportModels.ReportResult getCachedReport(String key) {
        try {
            // 1. محاولة الذاكرة أولاً
            ReportModels.ReportResult cached = memoryCache.get(key);
            if (cached != null) {
                cached.fromCache = true;
                return cached;
            }
            
            // 2. محاولة SharedPreferences
            String prefsData = prefs.getString(key, null);
            if (prefsData != null) {
                JSONObject data = new JSONObject(prefsData);
                ReportModels.ReportResult result = ReportModels.ReportResult.fromCache(data, key);
                memoryCache.put(key, result);
                return result;
            }
            
            // 3. محاولة القرص
            File file = new File(diskCacheDir, key + ".json");
            if (file.exists()) {
                byte[] bytes = new byte[(int) file.length()];
                FileInputStream fis = new FileInputStream(file);
                fis.read(bytes);
                fis.close();
                
                JSONObject data = new JSONObject(new String(bytes));
                ReportModels.ReportResult result = ReportModels.ReportResult.fromCache(data, key);
                memoryCache.put(key, result);
                return result;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached report: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * ✅ حفظ في القرص
     */
    private void saveToDisk(String key, String data) {
        try {
            File file = new File(diskCacheDir, key + ".json");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data.getBytes());
            fos.close();
            
            // تنظيف الملفات القديمة
            cleanupOldFiles();
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving to disk: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تنظيف الملفات القديمة (أقدم من 7 أيام)
     */
    private void cleanupOldFiles() {
        try {
            File[] files = diskCacheDir.listFiles();
            if (files != null) {
                long sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000);
                
                for (File file : files) {
                    if (file.lastModified() < sevenDaysAgo) {
                        file.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old files: " + e.getMessage());
        }
    }
    
    /**
     * ✅ مسح كل الكاش
     */
    public void clearAllCache() {
        memoryCache.evictAll();
        prefs.edit().clear().apply();
        
        if (diskCacheDir.exists()) {
            File[] files = diskCacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
        
        Log.d(TAG, "All cache cleared");
    }
    
    /**
     * ✅ مسح كاش تقرير محدد
     */
    public void clearCache(String key) {
        memoryCache.remove(key);
        prefs.edit().remove(key).apply();
        
        File file = new File(diskCacheDir, key + ".json");
        if (file.exists()) {
            file.delete();
        }
    }
    
    /**
     * ✅ الحصول على حجم الكاش
     */
    public long getCacheSize() {
        long size = 0;
        
        // حجم SharedPreferences
        size += prefs.getAll().size() * 100; // تقدير تقريبي
        
        // حجم القرص
        if (diskCacheDir.exists()) {
            File[] files = diskCacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    size += file.length();
                }
            }
        }
        
        return size;
    }
                     }
