package com.example.uhf.application;

import android.app.Application;
import android.util.Log;

import androidx.room.Room;

import com.example.uhf.database.InventoryDatabase;

/**
 * MiraApplication - الفئة الرئيسية للتطبيق
 * تدير دورة حياة قاعدة البيانات Singleton
 */
public class MiraApplication extends Application {
    
    private static final String TAG = "MiraApplication";
    private static final String DATABASE_NAME = "inventory_database";
    
    private static MiraApplication instance;
    private InventoryDatabase inventoryDatabase;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // تهيئة قاعدة البيانات مرة واحدة فقط
        initializeDatabase();
        
        Log.d(TAG, "MiraApplication initialized");
    }
    
    /**
     * تهيئة قاعدة البيانات Singleton
     */
    private void initializeDatabase() {
        inventoryDatabase = Room.databaseBuilder(
            getApplicationContext(),
            InventoryDatabase.class,
            DATABASE_NAME
        )
        .fallbackToDestructiveMigration()
        .build();
        
        Log.d(TAG, "Database initialized successfully");
    }
    
    /**
     * الحصول على مثيل التطبيق
     */
    public static synchronized MiraApplication getInstance() {
        return instance;
    }
    
    /**
     * الحصول على قاعدة البيانات
     */
    public InventoryDatabase getInventoryDatabase() {
        if (inventoryDatabase == null) {
            initializeDatabase();
        }
        return inventoryDatabase;
    }
    
    /**
     * إغلاق قاعدة البيانات عند إنهاء التطبيق
     */
    @Override
    public void onTerminate() {
        super.onTerminate();
        
        // إغلاق قاعدة البيانات عند إنهاء التطبيق
        if (inventoryDatabase != null) {
            try {
                inventoryDatabase.close();
                inventoryDatabase = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing database", e);
            }
        }
    }
}
