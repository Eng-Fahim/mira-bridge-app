package com.example.uhf.application;

import android.content.Context;
import android.util.Log;

import androidx.multidex.MultiDex;
import androidx.room.Room;

import com.example.uhf.AppContext;
import com.example.uhf.database.InventoryDatabase;
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * MiraApplication - الفئة الرئيسية للتطبيق
 * ترث من AppContext وتدير قاعدة البيانات Singleton
 */
public class MiraApplication extends AppContext {
    
    private static final String TAG = "MiraApplication";
    private static final String DATABASE_NAME = "inventory_database";
    
    private static MiraApplication instance;
    private InventoryDatabase inventoryDatabase;
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // تهيئة Firebase
        initFirebase();
        
        // تهيئة قاعدة البيانات مرة واحدة فقط
        initializeDatabase();
        
        Log.d(TAG, "MiraApplication initialized");
    }
    
    /**
     * تهيئة Firebase services
     */
    private void initFirebase() {
        try {
            FirebaseApp.initializeApp(this);
            
            // تعطيل Crashlytics في وضع التصحيح
            if (BuildConfig.DEBUG) {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
            } else {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed: " + e.getMessage());
        }
    }
    
    /**
     * تهيئة قاعدة البيانات Singleton
     */
    private void initializeDatabase() {
        try {
            inventoryDatabase = Room.databaseBuilder(
                getApplicationContext(),
                InventoryDatabase.class,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration()
            .build();
            
            Log.d(TAG, "Database initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Database initialization failed: " + e.getMessage());
        }
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
    
    /**
     * تنظيف الموارد عند انخفاض الذاكرة
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        System.gc();
    }
}
