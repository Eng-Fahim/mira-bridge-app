package com.example.uhf.application;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.room.Room;

import com.example.uhf.AppContext;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.manager.AppCrashHandler;
import com.example.uhf.manager.AppRestartManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.utils.MiraExecutorService;

public class MiraApplication extends AppContext {
    
    private static final String TAG = "MiraApplication";
    private static final String DATABASE_NAME = "inventory_database";
    
    private static volatile MiraApplication instance;
    private volatile InventoryDatabase inventoryDatabase;
    private volatile boolean isRestarting = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        instance = this;
        
        // ✅ 1. فقط الأشياء الخفيفة على Main Thread
        Thread.setDefaultUncaughtExceptionHandler(new AppCrashHandler(this));
        Log.i(TAG, "MiraApplication Started: onCreate()");
        
        // ✅ 2. تأجيل كل شيء آخر إلى background
        // هذا يمنع ANR عند بدء التطبيق
        
        new Thread(() -> {
            try {
                // ✅ Class.forName خفيف نسبياً — يمكن تركه
                initializeChainwaySdk();
                initializeExecutors();
                logDeviceInfo();
                
                Log.d(TAG, "✅ Background init complete");
            } catch (Exception e) {
                Log.e(TAG, "Background init error", e);
            }
        }, "MiraApp-Init").start();
        
        // ✅ 3. الخدمات الأساسية — تأجيل 500ms (بعد استقرار الواجهة)
        mainHandler.postDelayed(() -> {
            new Thread(() -> {
                try {
                    initializeCoreServices();
                    initializeDatabase();
                    Log.d(TAG, "✅ Delayed init complete");
                } catch (Exception e) {
                    Log.e(TAG, "Delayed init error", e);
                }
            }, "MiraApp-Services").start();
        }, 500);
        
        Log.d(TAG, "MiraApplication onCreate finished — heavy init deferred");
    }

    /**
     * ✅ تهيئة Chainway SDK
     */
    private void initializeChainwaySdk() {
        Log.i(TAG, "🔧 Initializing Chainway SDK...");
        
        try {
            Class<?> readerClass = Class.forName(
                "com.rscja.deviceapi.RFIDWithUHFUART");
            Log.i(TAG, "✅ Chainway RFIDWithUHFUART class found: " + 
                       readerClass.getName());
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "⚠️ Chainway SDK not found in classpath. " +
                       "Reader will fall back to simulator.");
        }
        
        String[] optionalClasses = {
            "com.rscja.deviceapi.entity.UHFTAGInfo",
            "com.imagealgorithmlab.fingerprint.FingerprintManager"
        };
        
        for (String className : optionalClasses) {
            try {
                Class.forName(className);
                Log.d(TAG, "✅ Found: " + className);
            } catch (ClassNotFoundException e) {
                Log.d(TAG, "ℹ️ Optional class not present: " + className);
            }
        }
        
        Log.i(TAG, "✅ Chainway SDK initialization complete");
    }

    private void initializeExecutors() {
        try {
            MiraExecutorService.getInstance();
            Log.d(TAG, "✅ MiraExecutorService initialized");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize MiraExecutorService", e);
        }
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
            
            Log.i(TAG, "Device Info: " + deviceInfo);
        } catch (Exception e) {
            Log.e(TAG, "logDeviceInfo error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تهيئة الخدمات الأساسية — الآن على Background Thread
     * 
     * ⚠️ ملاحظة: هذه الدالة تُستدعى الآن من new Thread في onCreate
     */
    private void initializeCoreServices() {
        Context appContext = getApplicationContext();
        
        try {
            Log.d(TAG, "Initializing Core Services (background)...");
            
            // ✅ الآن على background thread — آمن
            SessionManager sessionManager = new SessionManager(appContext);
            MiraServerConfig serverConfig = MiraServerConfig.getInstance(appContext);
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(appContext);
            MiraOrgProfileManager orgProfileManager = MiraOrgProfileManager.getInstance(appContext);
            PermissionManager permissionManager = PermissionManager.getInstance(appContext);
            
            Log.d(TAG, "✅ Core Services Initialized successfully");
            
        } catch (Exception e) {
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
                .build();
                
                Log.d(TAG, "✅ Database Initialized: Success");
            } catch (Exception e) {
                Log.e(TAG, "❌ Database initialization failed: " + e.getMessage(), e);
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
    
    public void cleanupOnRestart() {
        if (isRestarting) return;
        isRestarting = true;
        
        try {
            Log.i(TAG, "Cleaning up application resources...");
            
            if (inventoryDatabase != null) {
                try {
                    inventoryDatabase.close();
                    inventoryDatabase = null;
                    Log.d(TAG, "Database closed successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing database: " + e.getMessage());
                }
            }
            
            System.gc();
            
            Log.i(TAG, "Cleanup On Restart Completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage(), e);
        }
    }
    
    public void restartApp() {
        try {
            Log.i(TAG, "Manual restart requested");
            
            cleanupOnRestart();
            AppRestartManager.restartApp(this);
            
        } catch (Exception e) {
            Log.e(TAG, "Restart app error: " + e.getMessage(), e);
        }
    }
    
    public synchronized void reinitializeDatabase() {
        try {
            if (inventoryDatabase != null) {
                inventoryDatabase.close();
                inventoryDatabase = null;
            }
            initializeDatabase();
            Log.d(TAG, "Database Reinitialized: Success");
        } catch (Exception e) {
            Log.e(TAG, "Database reinitialization failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onTerminate() {
        Log.i(TAG, "Application Terminating: onTerminate()");
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
        Log.w(TAG, "Low Memory Warning");
        
        try {
            System.gc();
            if (inventoryDatabase != null && !inventoryDatabase.isOpen()) {
                inventoryDatabase = null;
                initializeDatabase();
            }
        } catch (Exception e) {
            Log.e(TAG, "Low memory cleanup error: " + e.getMessage());
        }
    }
    
    public boolean isAppHealthy() {
        try {
            if (inventoryDatabase != null && !inventoryDatabase.isOpen()) {
                Log.w(TAG, "Database is not open");
                return false;
            }
            
            if (MiraApiClient.getInstance(getApplicationContext()) == null) {
                Log.w(TAG, "API Client is null");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Health check error: " + e.getMessage());
            return false;
        }
    }
}
