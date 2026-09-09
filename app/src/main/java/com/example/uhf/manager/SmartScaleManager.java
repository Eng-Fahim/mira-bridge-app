package com.example.uhf.manager;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // سيتم طلب الإذن من النشاط الرئيسي
                    return;
                }
            }

            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                if (callback != null) callback.onScaleError("Bluetooth not supported");
                return;
            }

            if (!bluetoothAdapter.isEnabled()) {
                // سيتم تفعيل البلوتوث من النشاط الرئيسي
                return;
            }

            discoveredDevices.clear();

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
            context.registerReceiver(bluetoothReceiver, filter);

            bluetoothAdapter.startDiscovery();

        } catch (Exception e) {
            Log.e(TAG, "startDiscovery error: " + e.getMessage());
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
                return;
            }

            String[] deviceNames = new String[discoveredDevices.size()];
            for (int i = 0; i < discoveredDevices.size(); i++) {
                BluetoothDevice device = discoveredDevices.get(i);
                deviceNames[i] = device.getName() != null ? device.getName() : "Unknown " + device.getAddress();
            }

            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "📡 اختر الجهاز" : "📡 Select Device")
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
                    isArabic ? "📡 الاتصال عبر Bluetooth" : "📡 Connect via Bluetooth",
                    isArabic ? "🔍 البحث عن ميزان جديد" : "🔍 Search for new scale",
                    isArabic ? "🧪 محاكاة الميزان" : "🧪 Scale Simulation"
                };
            }

            new MaterialAlertDialogBuilder(context)
                .setTitle(isArabic ? "⚖️ إعدادات الميزان" : "⚖️ Scale Settings")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (isConnected()) {
                            disconnect();
                            deleteSavedScaleMac();
                        } else {
                            String savedMac = getSavedScaleMac();
                            if (savedMac != null && !savedMac.isEmpty()) {
                                connectToDevice(savedMac);
                            } else {
                                startDiscovery();
                            }
                        }
                    } else if (which == 1) {
                        if (!isConnected()) {
                            startDiscovery();
                        }
                    } else if (which == 2) {
                        // سيتم تنفيذ المحاكاة من الـ Fragment
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
