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
import com.example.uhf.tools.FirebaseDiagnostics; // ✅ إضافة جديدة
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
        
        // ✅ 1. تهيئة FirebaseDiagnostics
        FirebaseDiagnostics.init(this);
        FirebaseDiagnostics.log("SYSTEM", "MiraApplication Started", "onCreate()");
        
        // ✅ 2. تهيئة جميع الـ Managers بسياق التطبيق أولاً
        initializeCoreServices();
        
        // ✅ 3. تهيئة قاعدة البيانات
        initializeDatabase();
        
        // ✅ 4. تسجيل معلومات الجهاز
        logDeviceInfo();
        
        Log.d(TAG, "MiraApplication initialized successfully");
    }
    
    /**
     * ✅ تسجيل معلومات الجهاز للتشخيص
     */
    private void logDeviceInfo() {
        try {
            String deviceInfo = String.format(
                "Device: %s %s | Android: %s | App Version: %s",
                android.os.Build.MANUFACTURER,
                android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE,
                getPackageManager().getPackageInfo(getPackageName(), 0).versionName
            );
            
            FirebaseDiagnostics.log("DEVICE", "Device Info", deviceInfo);
        } catch (Exception e) {
            Log.e(TAG, "logDeviceInfo error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تهيئة جميع الخدمات الأساسية بسياق التطبيق
     */
    private void initializeCoreServices() {
        Context appContext = getApplicationContext();
        
        try {
            FirebaseDiagnostics.log("MANAGER", "Initializing Core Services", "Starting");
            
            // تهيئة SessionManager
            SessionManager sessionManager = new SessionManager(appContext);
            FirebaseDiagnostics.logManagerState("SessionManager", "INITIALIZED");
            Log.d(TAG, "✅ SessionManager initialized");
            
            // تهيئة MiraServerConfig
            MiraServerConfig serverConfig = MiraServerConfig.getInstance(appContext);
            FirebaseDiagnostics.logManagerState("MiraServerConfig", "INITIALIZED");
            Log.d(TAG, "✅ MiraServerConfig initialized");
            
            // تهيئة MiraApiClient
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            FirebaseDiagnostics.logManagerState("MiraApiClient", "INITIALIZED");
            Log.d(TAG, "✅ MiraApiClient initialized");
            
            // تهيئة MiraBranchesManager
            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(appContext);
            FirebaseDiagnostics.logManagerState("MiraBranchesManager", "INITIALIZED");
            Log.d(TAG, "✅ MiraBranchesManager initialized");
            
            // تهيئة MiraOrgProfileManager
            MiraOrgProfileManager orgProfileManager = MiraOrgProfileManager.getInstance(appContext);
            FirebaseDiagnostics.logManagerState("MiraOrgProfileManager", "INITIALIZED");
            Log.d(TAG, "✅ MiraOrgProfileManager initialized");
            
            // تهيئة PermissionManager
            PermissionManager permissionManager = PermissionManager.getInstance(appContext);
            FirebaseDiagnostics.logManagerState("PermissionManager", "INITIALIZED");
            Log.d(TAG, "✅ PermissionManager initialized");
            
            FirebaseDiagnostics.log("MANAGER", "Core Services Initialized", "All managers ready");
            
        } catch (Exception e) {
            FirebaseDiagnostics.logError("MiraApplication.initializeCoreServices", e);
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
            
            FirebaseDiagnostics.log("DATABASE", "Database Initialized", "Success");
            Log.d(TAG, "Database initialized successfully");
        } catch (Exception e) {
            FirebaseDiagnostics.logError("MiraApplication.initializeDatabase", e);
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
        FirebaseDiagnostics.log("SYSTEM", "Application Terminating", "onTerminate()");
        super.onTerminate();
        
        if (inventoryDatabase != null) {
            try {
                inventoryDatabase.close();
                inventoryDatabase = null;
                FirebaseDiagnostics.log("DATABASE", "Database Closed", "Success");
            } catch (Exception e) {
                FirebaseDiagnostics.logError("MiraApplication.onTerminate", e);
                Log.e(TAG, "Error closing database", e);
            }
        }
    }
    
    /**
     * تنظيف الموارد عند انخفاض الذاكرة
     */
    @Override
    public void onLowMemory() {
        FirebaseDiagnostics.log("MEMORY", "Low Memory Warning", "System.gc() called");
        super.onLowMemory();
        System.gc();
    }
}
