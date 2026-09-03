package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.GenericBluetoothReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;

public class RFIDReaderFactory {
    
    private static final String TAG = "RFIDReaderFactory";
    private static Context appContext;
    
    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }
    
    public static MiraIRFIDReader create(MiraIRFIDReader.ReaderType type, String address) {
        if (appContext == null) {
            Log.w(TAG, "⚠️ RFIDReaderFactory: Context is null. Call RFIDReaderFactory.init(context) first.");
        }

        if (type == null || type == MiraIRFIDReader.ReaderType.SIMULATOR) {
            return new SimulatorReader(appContext);
        }
        
        switch (type) {
            case CHAINWAY_C72:
            case CHAINWAY_C66:
            case CHAINWAY_C61:
            case CHAINWAY_C71:
            case CHAINWAY_R6:
                return new ChainwayReader(appContext);
                
            case HONEYWELL_EDA50:
            case UNITECH_RP902:
            case ZEBRA_MC3300:
            default:
                return new GenericBluetoothReader(address);
        }
    }
}
