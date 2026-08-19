package com.example.uhf.MiraRfid;

import android.content.Context;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.GenericBluetoothReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;

public class RFIDReaderFactory {
    
    private static Context appContext;
    
    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }
    
    public static MiraIRFIDReader create(MiraIRFIDReader.ReaderType type, String address) {
        if (type == null) {
            return new SimulatorReader(appContext);
        }
        
        if (type == MiraIRFIDReader.ReaderType.CHAINWAY_C72 ||
            type == MiraIRFIDReader.ReaderType.CHAINWAY_C66 ||
            type == MiraIRFIDReader.ReaderType.CHAINWAY_C61 ||
            type == MiraIRFIDReader.ReaderType.CHAINWAY_C71 ||
            type == MiraIRFIDReader.ReaderType.CHAINWAY_R6) {
            return new ChainwayReader(appContext);
        } 
        else if (type == MiraIRFIDReader.ReaderType.SIMULATOR) {
            return new SimulatorReader(appContext);
        } 
        else if (type == MiraIRFIDReader.ReaderType.HONEYWELL_EDA50 ||
                 type == MiraIRFIDReader.ReaderType.UNITECH_RP902 ||
                 type == MiraIRFIDReader.ReaderType.ZEBRA_MC3300) {
            // يمكنك إضافة Readers مخصصة لهذه الأجهزة لاحقاً
            return new GenericBluetoothReader(address);
        }
        else {
            return new GenericBluetoothReader(address);
        }
    }
}
