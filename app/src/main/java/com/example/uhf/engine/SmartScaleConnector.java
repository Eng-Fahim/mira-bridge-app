package com.example.uhf.engine;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 🟢 موصل الميزان الذكي عبر Bluetooth SPP
 */
public class SmartScaleConnector {

    private static final String TAG = "SmartScale";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScaleListener listener;
    private volatile boolean connected = false;

    public interface ScaleListener {
        void onWeightReceived(double weightGrams);
        void onScaleConnected(String deviceName);
        void onScaleDisconnected();
        void onScaleError(String error);
    }

    public void setListener(ScaleListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }
    
    /**
     * الاتصال بالميزان عبر MAC Address
     */
    public void connectToDevice(String macAddress) {
        if (macAddress == null || macAddress.isEmpty()) {
            if (listener != null) listener.onScaleError("MAC Address is empty");
            return;
        }

        new Thread(() -> {
            try {
                disconnect(); // إغلاق أي اتصال قديم أولاً
                
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter == null) {
                    if (listener != null) listener.onScaleError("Bluetooth not supported");
                    return;
                }
                
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                connected = true;

                final String deviceName = device.getName() != null ? device.getName() : "Smart Scale";
                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleConnected(deviceName);
                    }
                });

                startReading();

            } catch (Exception e) {
                Log.e(TAG, "Connect error: " + e.getMessage());
                connected = false;
                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleError("فشل الاتصال بالميزان: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * محاولة الاتصال التلقائي بالميزان الذكي المحفوظ مسبقاً
     */
    public void connect() {
        // هذه الدالة يمكن تنفيذها لاحقاً
        Log.i(TAG, "Auto-connect triggered. Use connectToDevice(String macAddress) instead.");
    }

    public void connect(BluetoothDevice device) {
        if (device == null) {
            if (listener != null) listener.onScaleError("Device is null");
            return;
        }

        new Thread(() -> {
            try {
                disconnect(); // إغلاق أي اتصال قديم أولاً
                
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                connected = true;

                final String deviceName = device.getName() != null ? device.getName() : "Smart Scale";
                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleConnected(deviceName);
                    }
                });

                startReading();

            } catch (Exception e) {
                Log.e(TAG, "Connect error: " + e.getMessage());
                connected = false;
                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleError("فشل الاتصال بالميزان: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void startReading() {
        byte[] buffer = new byte[256];
        while (connected) {
            try {
                if (inputStream == null) break;
                int bytes = inputStream.read(buffer);
                if (bytes > 0) {
                    String rawData = new String(buffer, 0, bytes);
                    double weight = parseWeight(rawData);
                    if (weight > 0) {
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onWeightReceived(weight);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                if (connected) {
                    Log.e(TAG, "Read error: " + e.getMessage());
                    disconnect();
                }
                break;
            }
        }
    }

    private double parseWeight(String rawData) {
        try {
            if (rawData == null || rawData.trim().isEmpty()) return 0;
            
            // تنقية وتجميع الأرقام والنقاط العشرية
            String cleaned = rawData.replaceAll("[^0-9.]", " ").trim();
            String[] parts = cleaned.split("\\s+");
            
            for (String part : parts) {
                if (!part.isEmpty() && part.matches("\\d+\\.?\\d*")) {
                    double val = Double.parseDouble(part);
                    if (val > 0) return val;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return 0;
    }

    public void sendCommand(String command) {
        new Thread(() -> {
            try {
                if (outputStream != null && connected) {
                    outputStream.write((command + "\r\n").getBytes());
                    outputStream.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Send error: " + e.getMessage());
            }
        }).start();
    }

    public void tare() {
        sendCommand("T");
    }

    public void disconnect() {
        connected = false;
        try {
            if (inputStream != null) { inputStream.close(); inputStream = null; }
            if (outputStream != null) { outputStream.close(); outputStream = null; }
            if (socket != null) { socket.close(); socket = null; }
        } catch (Exception e) {
            Log.e(TAG, "Disconnect error: " + e.getMessage());
        }
        handler.post(() -> {
            if (listener != null) {
                listener.onScaleDisconnected();
            }
        });
    }
}
