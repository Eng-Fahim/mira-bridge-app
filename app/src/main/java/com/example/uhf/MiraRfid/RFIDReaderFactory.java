package com.example.uhf.MiraRfid;

import com.example.uhf.MiraRfid.readers.GenericBluetoothReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;

/**
 * مصنع إنشاء القارئات
 */
public class RFIDReaderFactory {
    
    /**
     * إنشاء القارئ المناسب حسب النوع
     */
    public static MiraIRFIDReader create(MiraIRFIDReader.ReaderType type, String address) {
        switch (type) {
            case GENERIC_BLUETOOTH:
                return new GenericBluetoothReader(address);
                
            case SIMULATOR:
            default:
                return new SimulatorReader();
        }
    }
}
