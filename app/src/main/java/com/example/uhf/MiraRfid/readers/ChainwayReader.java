package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

/**
 * قارئ Chainway حقيقي - يستخدم DeviceAPI SDK
 */
public class ChainwayReader implements MiraIRFIDReader {
    
    private static final String TAG = "ChainwayReader";
    
    private Context context;
    private RFIDWithUHFUART reader;
    private OnTagReadListener listener;
    private boolean isScanning = false;
    private boolean isConnected = false;
    private Thread scanThread;
    
    public ChainwayReader(Context context) {
        this.context = context;
    }
    
    @Override
    public boolean initialize() {
        try {
            reader = RFIDWithUHFUART.getInstance();
            boolean success = reader.init(context);
            Log.i(TAG, "Chainway init: " + success);
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Init error: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean connect() {
        try {
            if (reader == null) {
                initialize();
            }
            isConnected = reader != null;
            if (isConnected && listener != null) {
                listener.onConnectionChanged(true);
            }
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "Connect error: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) {
            listener.onConnectionChanged(false);
        }
    }
    
    @Override
    public void release() {
        disconnect();
        try {
            if (reader != null) {
                reader.free();
                reader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Release error: " + e.getMessage());
        }
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (reader == null) return false;
        
        try {
            boolean success = reader.startInventoryTag();
            if (success) {
                isScanning = true;
                if (listener != null) listener.onScanStarted();
                
                // ✅ بدء قراءة النتائج - readTagFromBuffer ترجع UHFTAGInfo واحد
                scanThread = new Thread(() -> {
                    while (isScanning) {
                        try {
                            UHFTAGInfo tag = reader.readTagFromBuffer();
                            if (tag != null) {
                                String epc = tag.getEPC();
                                // ✅ getRssi ترجع String
                                String rssiStr = tag.getRssi();
                                int rssi = -50;
                                if (rssiStr != null) {
                                    try { 
                                        rssi = Integer.parseInt(rssiStr.trim()); 
                                    } catch (NumberFormatException ignored) {}
                                }
                                if (epc != null && !epc.isEmpty() && listener != null) {
                                    listener.onTagRead(epc, rssi);
                                }
                            }
                            Thread.sleep(100);
                        } catch (Exception e) {
                            if (isScanning && listener != null) {
                                listener.onError(e.getMessage());
                            }
                            break;
                        }
                    }
                });
                scanThread.start();
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Start scan error: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        try {
            if (reader != null) {
                reader.stopInventory();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop scan error: " + e.getMessage());
        }
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        try {
            if (reader != null) {
                // ✅ استخدام الدالة الصحيحة من SDK
                reader.setPower(powerDbm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Set power error: " + e.getMessage());
        }
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        Log.i(TAG, "RSSI threshold: " + threshold);
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        Log.i(TAG, "Scan interval: " + intervalMs);
    }
    
    @Override
    public String getDeviceName() {
        try {
            if (reader != null) {
                return "Chainway " + reader.getVersion();
            }
        } catch (Exception ignored) {}
        return "Chainway UHF Reader";
    }
    
    @Override
    public String getFirmwareVersion() {
        try {
            if (reader != null) {
                return reader.getVersion();
            }
        } catch (Exception ignored) {}
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
}
