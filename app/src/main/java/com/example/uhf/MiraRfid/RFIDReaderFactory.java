package com.example.uhf.MiraRfid;

import android.content.Context;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.GenericBluetoothReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;

public class RFIDReaderFactory {
    
    private static Context appContext;
    
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }
    
    public static MiraIRFIDReader create(MiraIRFIDReader.ReaderType type, String address) {
        switch (type) {
            // ✅ Chainway - يعمل فعلياً
            case CHAINWAY_C72:
            case CHAINWAY_C61:
            case CHAINWAY_C66:
            case CHAINWAY_C71:
                return new ChainwayReader(appContext);
                
            // ✅ Bluetooth SPP - يعمل فعلياً
            case GENERIC_BLUETOOTH:
            case TSL_1128:
            case TSL_2128:
            case TSL_3166:
            case NORDIC_ID_NUR2:
            case IMPINJ_RS1000:
            case ZEBRA_RFD8500:
            case ZEBRA_RFD40:
                return new GenericBluetoothReader(address);
                
            // ⚠️ باقي الأجهزة - محاكي مؤقتاً
            case SIMULATOR:
            default:
                return new SimulatorReader();
        }
    }
}
