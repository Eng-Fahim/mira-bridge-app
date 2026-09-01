package com.example.uhf.application;

import android.content.Context;
import android.util.Log;

import androidx.multidex.MultiDex;
import androidx.room.Room;

import com.example.uhf.AppContext;
import com.example.uhf.BuildConfig;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

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
        super.onCreate(); // ✅ هذا يستدعي initFirebase() و ToastUtil.init() من AppContext
        instance = this;
        
        // ✅ 1. تهيئة جميع الـ Managers بسياق التطبيق أولاً
        initializeCoreServices();
        
        // ✅ 2. تهيئة قاعدة البيانات
        initializeDatabase();
        
        Log.d(TAG, "MiraApplication initialized successfully");
    }
    
    /**
     * ✅ تهيئة جميع الخدمات الأساسية بسياق التطبيق
     */
    private void initializeCoreServices() {
        Context appContext = getApplicationContext();
        
        try {
            // تهيئة SessionManager
            SessionManager sessionManager = new SessionManager(appContext);
            Log.d(TAG, "✅ SessionManager initialized");
            
            // تهيئة MiraServerConfig
            MiraServerConfig serverConfig = MiraServerConfig.getInstance(appContext);
            Log.d(TAG, "✅ MiraServerConfig initialized");
            
            // تهيئة MiraApiClient
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            Log.d(TAG, "✅ MiraApiClient initialized");
            
            // تهيئة MiraBranchesManager
            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(appContext);
            Log.d(TAG, "✅ MiraBranchesManager initialized");
            
            // تهيئة MiraOrgProfileManager
            MiraOrgProfileManager orgProfileManager = MiraOrgProfileManager.getInstance(appContext);
            Log.d(TAG, "✅ MiraOrgProfileManager initialized");
            
            // تهيئة PermissionManager
            PermissionManager permissionManager = PermissionManager.getInstance(appContext);
            Log.d(TAG, "✅ PermissionManager initialized");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing core services: " + e.getMessage(), e);
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
