package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

/**
 * قارئ Chainway حقيقي - يدعم القراءة والكتابة
 */
public class ChainwayReader implements MiraIRFIDReader {
    
    private static final String TAG = "ChainwayReader";
    
    private Context context;
    private RFIDWithUHFUART reader;
    private OnTagReadListener listener;
    private boolean isScanning = false;
    private boolean isConnected = false;
    
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
            return false;
        }
    }
    
    // ✅ دوال جديدة للقراءة والكتابة
    
    /**
     * قراءة EPC من الشريحة
     */
    public String readEpcData(String accessPassword) {
        if (reader == null) return null;
        
        try {
            // قراءة EPC من البنك EPC، العنوان 32، الطول 6
            String epcData = reader.readData(accessPassword, 
                RFIDWithUHFUART.Bank_EPC, 32, 6);
            
            if (epcData != null && !epcData.isEmpty()) {
                return epcData;
            }
        } catch (Exception e) {
            Log.e(TAG, "readEpcData error: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * قراءة TID من الشريحة
     */
    public String readTidData(String accessPassword) {
        if (reader == null) return null;
        
        try {
            String tidData = reader.readData(accessPassword, 
                RFIDWithUHFUART.Bank_TID, 0, 6);
            
            if (tidData != null && !tidData.isEmpty()) {
                return tidData;
            }
        } catch (Exception e) {
            Log.e(TAG, "readTidData error: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * كتابة EPC على الشريحة
     */
    public boolean writeEpcData(String newEpc, String accessPassword) {
        if (reader == null) return false;
        
        try {
            int writeLen = newEpc.length() / 4;
            boolean success = reader.writeData(accessPassword, 
                RFIDWithUHFUART.Bank_EPC, 32, writeLen, newEpc);
            
            return success;
        } catch (Exception e) {
            Log.e(TAG, "writeEpcData error: " + e.getMessage());
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
                
                new Thread(() -> {
                    while (isScanning) {
                        try {
                            UHFTAGInfo tag;
                            while (isScanning && (tag = reader.readTagFromBuffer()) != null) {
                                String epc = tag.getEPC();
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
                            Thread.sleep(10);
                        } catch (Exception e) {
                            if (isScanning && listener != null) {
                                listener.onError(e.getMessage());
                            }
                            break;
                        }
                    }
                }).start();
            }
            return success;
        } catch (Exception e) {
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
                reader.setPower(powerDbm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Set power error: " + e.getMessage());
        }
    }
    
    @Override
    public void setRssiThreshold(int threshold) {}
    
    @Override
    public void setScanInterval(int intervalMs) {}
    
    @Override
    public String getDeviceName() {
        return "Chainway C72";
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
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        return writeEpcData(newEpc, accessPassword);
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return false;
    }
    
    @Override
    public String readTid() {
        return readTidData("00000000");
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return null;
    }
    
    @Override
    public boolean lockTag(String accessPassword) {
        return false;
    }
    
    @Override
    public boolean unlockTag(String accessPassword) {
        return false;
    }
    
    @Override
    public boolean killTag(String accessPassword) {
        return false;
    }
}
