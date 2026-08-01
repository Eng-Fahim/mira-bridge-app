package com.example.uhf.engine;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 🟢 موصل الميزان الذكي عبر Bluetooth
 * 
 * يدعم الموازين الإلكترونية الذكية لقراءة الوزن الفعلي للقطع
 */
public class SmartScaleConnector {

    private static final String TAG = "SmartScale";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ScaleListener listener;
    private boolean connected = false;

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
     * 🟢 الاتصال بميزان Bluetooth
     */
    public void connect(BluetoothDevice device) {
        new Thread(() -> {
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                connected = true;

                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleConnected(device.getName());
                    }
                });

                // 🟢 بدء قراءة الوزن
                startReading();

            } catch (Exception e) {
                Log.e(TAG, "Connect error: " + e.getMessage());
                handler.post(() -> {
                    if (listener != null) {
                        listener.onScaleError(e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * 🟢 قراءة مستمرة للوزن
     */
    private void startReading() {
        byte[] buffer = new byte[256];
        while (connected) {
            try {
                int bytes = inputStream.read(buffer);
                if (bytes > 0) {
                    String data = new String(buffer, 0, bytes).trim();
                    double weight = parseWeight(data);
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
            }
        }
    }

    /**
     * 🟢 استخراج الوزن من بيانات الميزان
     */
    private double parseWeight(String rawData) {
        try {
            // صيغ شائعة للموازين:
            // "ST,GS, 5.200,g" ← وزن بالجرام
            // "+ 12.800 g"
            // "5.200g"
            String cleaned = rawData.replaceAll("[^0-9.]", " ").trim();
            String[] parts = cleaned.split("\\s+");
            for (String part : parts) {
                if (!part.isEmpty() && part.matches("\\d+\\.?\\d*")) {
                    return Double.parseDouble(part);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 🟢 إرسال أمر للميزان
     */
    public void sendCommand(String command) {
        try {
            if (outputStream != null) {
                outputStream.write((command + "\r\n").getBytes());
            }
        } catch (Exception e) {
            Log.e(TAG, "Send error: " + e.getMessage());
        }
    }

    /**
     * 🟢 تصفير الميزان (Tare)
     */
    public void tare() {
        sendCommand("T");
    }

    /**
     * 🟢 فصل الاتصال
     */
    public void disconnect() {
        connected = false;
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
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
