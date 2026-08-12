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
    public static IRFIDReader create(IRFIDReader.ReaderType type, String address) {
        switch (type) {
            case GENERIC_BLUETOOTH:
                return new GenericBluetoothReader(address);
                
            case SIMULATOR:
            default:
                // جميع الأنواع غير المنفذة بعد تستخدم المحاكي
                return new SimulatorReader();
        }
    }
}
