package com.example.uhf.application;

import android.content.Context;
import android.util.Log;

import androidx.room.Room;

import com.example.uhf.AppContext;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.FirebaseDiagnostics;

public class MiraApplication extends AppContext {
    
    private static final String TAG = "MiraApplication";
    private static final String DATABASE_NAME = "inventory_database";
    
    private static volatile MiraApplication instance;
    private volatile InventoryDatabase inventoryDatabase;
    
    @Override
    public void onCreate() {
        super.onCreate(); // يقوم بتأكيد mApp وإعداد AppContext
        instance = this;
        
        FirebaseDiagnostics.init(this);
        FirebaseDiagnostics.log("SYSTEM", "MiraApplication Started", "onCreate()");
        
        initializeCoreServices();
        initializeDatabase();
        logDeviceInfo();
        
        Log.d(TAG, "MiraApplication initialized successfully");
    }
    
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
    
    private void initializeCoreServices() {
        Context appContext = getApplicationContext();
        
        try {
            FirebaseDiagnostics.log("MANAGER", "Initializing Core Services", "Starting");
            
            // إعادة تصفير وبناء الحالات بانتظام لمنع بقاء الذاكرة قديمة عند إعادة التشغيل
            SessionManager sessionManager = new SessionManager(appContext);
            MiraServerConfig serverConfig = MiraServerConfig.getInstance(appContext);
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(appContext);
            MiraOrgProfileManager orgProfileManager = MiraOrgProfileManager.getInstance(appContext);
            PermissionManager permissionManager = PermissionManager.getInstance(appContext);
            
            FirebaseDiagnostics.log("MANAGER", "Core Services Initialized", "All managers ready");
            
        } catch (Exception e) {
            FirebaseDiagnostics.logError("MiraApplication.initializeCoreServices", e);
            Log.e(TAG, "❌ Error initializing core services: " + e.getMessage(), e);
        }
    }
    
    private synchronized void initializeDatabase() {
        if (inventoryDatabase == null) {
            try {
                inventoryDatabase = Room.databaseBuilder(
                    getApplicationContext(),
                    InventoryDatabase.class,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries() // يمنع التعطيل في العمليات الاستعلامية السريعة
                .build();
                
                FirebaseDiagnostics.log("DATABASE", "Database Initialized", "Success");
            } catch (Exception e) {
                FirebaseDiagnostics.logError("MiraApplication.initializeDatabase", e);
                Log.e(TAG, "Database initialization failed: " + e.getMessage());
            }
        }
    }
    
    public static MiraApplication getInstance() {
        if (instance == null) {
            synchronized (MiraApplication.class) {
                if (instance == null) {
                    if (AppContext.getInstance() instanceof MiraApplication) {
                        instance = (MiraApplication) AppContext.getInstance();
                    }
                }
            }
        }
        return instance;
    }
    
    public InventoryDatabase getInventoryDatabase() {
        if (inventoryDatabase == null) {
            initializeDatabase();
        }
        return inventoryDatabase;
    }
    
    @Override
    public void onTerminate() {
        FirebaseDiagnostics.log("SYSTEM", "Application Terminating", "onTerminate()");
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
    
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        FirebaseDiagnostics.log("MEMORY", "Low Memory Warning", "System.gc() called");
        System.gc();
    }
}
