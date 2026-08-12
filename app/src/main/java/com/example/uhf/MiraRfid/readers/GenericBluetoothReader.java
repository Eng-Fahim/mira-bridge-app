package com.example.uhf.MiraRfid.readers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.example.uhf.MiraRfid.IRFIDReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * قارئ Bluetooth عام يستخدم بروتوكول SPP
 */
public class GenericBluetoothReader implements IRFIDReader {
    
    private static final String TAG = "GenericBluetoothReader";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private final String deviceAddress;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    
    private OnTagReadListener listener;
    private boolean isScanning = false;
    private boolean isConnected = false;
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private Thread readThread;
    
    public GenericBluetoothReader(String deviceAddress) {
        this.deviceAddress = deviceAddress;
    }
    
    @Override
    public boolean initialize() {
        return deviceAddress != null && !deviceAddress.isEmpty();
    }
    
    @Override
    public boolean connect() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            isConnected = true;
            
            if (listener != null) listener.onConnectionChanged(true);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void disconnect() {
        stopScan();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        isConnected = false;
        if (listener != null) listener.onConnectionChanged(false);
    }
    
    @Override
    public void release() {
        disconnect();
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (!isConnected && !connect()) return false;
        
        isScanning = true;
        if (listener != null) listener.onScanStarted();
        
        sendCommand("START\r\n");
        
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (isScanning) {
                try {
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        String data = new String(buffer, 0, bytes).trim();
                        parseData(data);
                    }
                } catch (IOException e) {
                    if (isScanning && listener != null) {
                        listener.onError(e.getMessage());
                    }
                    break;
                }
            }
        });
        readThread.start();
        
        return true;
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        sendCommand("STOP\r\n");
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        sendCommand("POWER " + powerDbm + "\r\n");
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
        sendCommand("RSSI " + threshold + "\r\n");
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = intervalMs;
    }
    
    @Override
    public String getDeviceName() {
        return "Bluetooth Reader";
    }
    
    @Override
    public String getFirmwareVersion() {
        return "Unknown";
    }
    
    @Override
    public int getBatteryLevel() {
        return -1;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    private void sendCommand(String command) {
        try {
            if (outputStream != null) {
                outputStream.write(command.getBytes());
                outputStream.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
        }
    }
    
    private void parseData(String data) {
        String[] lines = data.split("[\\r\\n]+");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            String epc;
            int rssi = -50;
            
            if (line.contains(",")) {
                String[] parts = line.split(",");
                epc = parts[0].trim();
                if (parts.length > 1) {
                    try { rssi = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) {}
                }
            } else {
                epc = line;
            }
            
            if (listener != null && !epc.isEmpty()) {
                listener.onTagRead(epc, rssi);
            }
        }
    }
}
