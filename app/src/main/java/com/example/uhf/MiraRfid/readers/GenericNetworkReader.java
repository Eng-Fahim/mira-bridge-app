package com.example.uhf.MiraRfid.readers;
import android.content.Context;
import com.example.uhf.MiraRfid.MiraIRFIDReader;

/**
 * قارئ شبكة TCP عام
 */
public class GenericNetworkReader extends SimulatorReader {
    public GenericNetworkReader(Context context) {
        super(context);
    }
    
    public GenericNetworkReader() {
        super(null);
    }
}
