package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.GenericBluetoothReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.MiraRfid.readers.VanchReader;

/**
 * ✅ RFIDReaderFactory v3.2 — يدعم VANCH Fixed Reader
 */
public class RFIDReaderFactory {

    private static final String TAG = "RFIDReaderFactory";

    /** البورت الافتراضي لقارئات VANCH الثابتة */
    private static final int VANCH_DEFAULT_PORT = 4001;

    private static Context appContext;

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static MiraIRFIDReader create(MiraIRFIDReader.ReaderType type, String address) {
        if (appContext == null) {
            Log.w(TAG, "⚠️ RFIDReaderFactory: Context is null. Call init(context) first.");
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

            // ✅ [جديد] قارئ VANCH الثابت
            case VANCH_FIXED: {
                String host = parseHost(address);
                int port = parsePort(address, VANCH_DEFAULT_PORT);

                Log.i(TAG, "🔌 Creating VanchReader: host=" + host + " port=" + port);
                return new VanchReader(appContext, host, port);
            }

            case HONEYWELL_EDA50:
            case UNITECH_RP902:
            case ZEBRA_MC3300:
            default:
                return new GenericBluetoothReader(address);
        }
    }

    /**
     * ✅ استخراج IP/host من العنوان
     * يقبل: "192.168.1.100" أو "192.168.1.100:4001"
     */
    private static String parseHost(String address) {
        if (address == null || address.isEmpty()) {
            return "192.168.1.100";  // افتراضي
        }
        int colonIdx = address.indexOf(':');
        if (colonIdx > 0) {
            return address.substring(0, colonIdx).trim();
        }
        return address.trim();
    }

    /**
     * ✅ استخراج البورت من العنوان
     */
    private static int parsePort(String address, int defaultPort) {
        if (address == null || address.isEmpty()) {
            return defaultPort;
        }
        int colonIdx = address.indexOf(':');
        if (colonIdx > 0 && colonIdx < address.length() - 1) {
            try {
                return Integer.parseInt(address.substring(colonIdx + 1).trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "⚠️ Invalid port in address: " + address);
            }
        }
        return defaultPort;
    }
}
