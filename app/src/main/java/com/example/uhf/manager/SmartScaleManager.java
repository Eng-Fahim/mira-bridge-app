package com.example.uhf.manager;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.example.uhf.engine.SmartScaleConnector;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

public class SmartScaleManager {

    private static final String TAG = "SmartScaleManager";
    private static final String PREFS_NAME = "scale_settings";
    private static final String PREFS_SCALE_MAC = "scale_mac_address";
    private static final String PREFS_SCALE_NAME = "scale_name";
    private static final int REQUEST_BLUETOOTH_PERMISSION = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;

    public interface ScaleCallback {
        void onWeightReceived(double weight);
        void onScaleConnected(String name);
        void onScaleDisconnected();
        void onScaleError(String error);
    }

    private SmartScaleConnector scaleConnector;
    private Context context;
    private ScaleCallback callback;
    private ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private BroadcastReceiver bluetoothReceiver;
    private boolean isArabic = false;

    public SmartScaleManager(Context context, ScaleCallback callback) {
        this.context = context;
        this.callback = callback;
        try {
            this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
        } catch (Exception e) {
            this.isArabic = false;
        }
        initScaleConnector();
    }

    private void initScaleConnector() {
        try {
            scaleConnector = new SmartScaleConnector();
            scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
                @Override
                public void onWeightReceived(double w) {
                    if (callback != null) callback.onWeightReceived(w);
                }

                @Override
                public void onScaleConnected(String n) {
                    if (callback != null) callback.onScaleConnected(n);
                }

                @Override
                public void onScaleDisconnected() {
                    if (callback != null) callback.onScaleDisconnected();
                }

                @Override
                public void onScaleError(String e) {
                    if (callback != null) callback.onScaleError(e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "initScaleConnector error: " + e.getMessage());
        }
    }

    // ==================== الاتصال ====================

    public void connect() {
        if (scaleConnector != null) {
            scaleConnector.connect();
        }
        autoConnect();
    }

    public void autoConnect() {
        try {
            String savedMac = getSavedScaleMac();
            if (savedMac != null && !savedMac.isEmpty()) {
                scaleConnector.connectToDevice(savedMac);
            }
        } catch (Exception e) {
            Log.e(TAG, "autoConnect error: " + e.getMessage());
        }
    }

    public void connectToDevice(String macAddress) {
        if (scaleConnector != null) {
            scaleConnector.connectToDevice(macAddress);
        }
    }

    public void disconnect() {
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
    }

    public boolean isConnected() {
        return scaleConnector != null && scaleConnector.isConnected();
    }

    // ==================== البحث عن الأجهزة ====================

    public void startDiscovery() {
        try {
            // 1) فحص صلاحيات البلوتوث (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    
                    Toast.makeText(context, 
                        isArabic ? "⚠️ يرجى إعطاء صلاحيات البلوتوث للتطبيق من الإعدادات" : "⚠️ Please grant Bluetooth permissions in Settings", 
                        Toast.LENGTH_LONG).show();
                    return;
                }
            } 
            // 2) فحص صلاحية الموقع (مطلوبة للبحث عن البلوتوث في Android 6 إلى 11)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, 
                        isArabic ? "⚠️ يرجى إعطاء صلاحية الموقع للبحث عن الميزان" : "⚠️ Location permission required to find scale", 
                        Toast.LENGTH_LONG).show();
                    return;
                }
            }

            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                if (callback != null) callback.onScaleError("Bluetooth not supported");
                Toast.makeText(context, isArabic ? "الجهاز لا يدعم البلوتوث" : "Bluetooth not supported", Toast.LENGTH_SHORT).show();
                return;
            }

            // 3) فحص تشغيل البلوتوث
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(context, 
                    isArabic ? "⚠️ يرجى تشغيل البلوتوث أولاً" : "⚠️ Please turn on Bluetooth first", 
                    Toast.LENGTH_LONG).show();
                
                // محاولة فتح شاشة تشغيل البلوتوث تلقائياً
                try {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    context.startActivity(enableBtIntent);
                } catch (Exception ex) {
                    Log.e(TAG, "Cannot launch BT enable intent: " + ex.getMessage());
                }
                return;
            }

            // 4) بدء البحث وإظهار رسالة للمستخدم
            discoveredDevices.clear();
            Toast.makeText(context, 
                isArabic ? "🔍 جاري البحث عن أجهزة..." : "🔍 Searching for devices...", 
                Toast.LENGTH_SHORT).show();

            // إلغاء البحث السابق إن وجد
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }

            // إلغاء تسجيل المستقبل القديم إن وجد
            if (bluetoothReceiver != null) {
                try { context.unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
                bluetoothReceiver = null;
            }

            bluetoothReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (device != null) {
                            if (!discoveredDevices.contains(device)) {
                                discoveredDevices.add(device);
                                Log.d(TAG, "Discovered: " + device.getName() + " - " + device.getAddress());
                            }
                        }
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                        showDiscoveredDevices();
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            
            // ✅ إصلاح توافقية أندرويد 13+ (API 33)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(bluetoothReceiver, filter);
            }

            boolean started = bluetoothAdapter.startDiscovery();
            if (!started) {
                Toast.makeText(context, isArabic ? "فشل بدء البحث" : "Failed to start search", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "startDiscovery error: " + e.getMessage());
            Toast.makeText(context, "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDiscoveredDevices() {
        try {
            if (bluetoothReceiver != null) {
                try {
                    context.unregisterReceiver(bluetoothReceiver);
                } catch (Exception e) {
                    Log.e(TAG, "Unregister error: " + e.getMessage());
                }
                bluetoothReceiver = null;
            }

            if (discoveredDevices == null || discoveredDevices.isEmpty()) {
                Toast.makeText(context, 
                    isArabic ? "لم يتم العثور على أي أجهزة. تأكد من تشغيل الميزان." : "No devices found. Ensure scale is on.", 
                    Toast.LENGTH_LONG).show();
                return;
            }

            String[] deviceNames = new String[discoveredDevices.size()];
            for (int i = 0; i < discoveredDevices.size(); i++) {
                BluetoothDevice device = discoveredDevices.get(i);
                String name = null;
                try {
                    name = device.getName();
                } catch (SecurityException se) {
                    // قد يحدث هذا في بعض الأجهزة إذا لم يتم إعطاء صلاحية CONNECT بشكل صحيح
                }
                deviceNames[i] = (name != null) ? name : "Unknown " + device.getAddress();
            }

            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "📡 اختر الميزان" : "📡 Select Scale")
                .setItems(deviceNames, (dialog, which) -> {
                    BluetoothDevice selectedDevice = discoveredDevices.get(which);
                    connectToDevice(selectedDevice.getAddress());
                    saveScaleMac(selectedDevice.getAddress());
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();

        } catch (Exception e) {
            Log.e(TAG, "showDiscoveredDevices error: " + e.getMessage());
        }
    }

    // ==================== الحفظ والاسترجاع ====================

    private void saveScaleMac(String macAddress) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREFS_SCALE_MAC, macAddress).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveScaleMac error: " + e.getMessage());
        }
    }

    private String getSavedScaleMac() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(PREFS_SCALE_MAC, null);
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteSavedScaleMac() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(PREFS_SCALE_MAC).apply();
        } catch (Exception e) {
            Log.e(TAG, "deleteSavedScaleMac error: " + e.getMessage());
        }
    }

    public String getScaleName() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(PREFS_SCALE_NAME, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== إعدادات الميزان ====================

    public void showSettingsDialog() {
        try {
            String[] options;
            if (isConnected()) {
                options = new String[]{
                    isArabic ? "🔌 قطع الاتصال" : "🔌 Disconnect",
                    isArabic ? "🧪 محاكاة الميزان" : "🧪 Scale Simulation"
                };
            } else {
                options = new String[]{
                    isArabic ? "📡 الاتصال بالميزان المحفوظ" : "📡 Connect to Saved Scale",
                    isArabic ? "🔍 البحث عن ميزان جديد" : "🔍 Search for new scale",
                    isArabic ? "🧪 محاكاة الميزان" : "🧪 Scale Simulation"
                };
            }

            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "⚖️ إعدادات الميزان" : "⚖️ Scale Settings")
                .setItems(options, (dialog, which) -> {
                    if (isConnected()) {
                        // الخيارات عندما يكون متصلاً
                        if (which == 0) {
                            disconnect();
                            deleteSavedScaleMac();
                        } else if (which == 1) {
                            Toast.makeText(context, isArabic ? "المحاكاة تعمل من شاشة البيع" : "Simulation runs from POS screen", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // الخيارات عندما يكون غير متصل
                        if (which == 0) {
                            String savedMac = getSavedScaleMac();
                            if (savedMac != null && !savedMac.isEmpty()) {
                                connectToDevice(savedMac);
                            } else {
                                startDiscovery();
                            }
                        } else if (which == 1) {
                            startDiscovery();
                        } else if (which == 2) {
                            Toast.makeText(context, isArabic ? "المحاكاة تعمل من شاشة البيع" : "Simulation runs from POS screen", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();

        } catch (Exception e) {
            Log.e(TAG, "showSettingsDialog error: " + e.getMessage());
        }
    }

    public void sendTestWeight(double weight) {
        if (scaleConnector != null) {
            scaleConnector.sendTestWeight(weight);
        }
    }

    public void cleanup() {
        if (bluetoothReceiver != null) {
            try {
                context.unregisterReceiver(bluetoothReceiver);
            } catch (Exception e) {
                Log.e(TAG, "cleanup receiver error: " + e.getMessage());
            }
            bluetoothReceiver = null;
        }
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
    }
}
