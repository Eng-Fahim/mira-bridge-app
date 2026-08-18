package com.example.uhf.MiraRfid.readers;

import com.example.uhf.MiraRfid.MiraIRFIDReader;

/**
 * قارئ USB عام
 */
public class GenericUsbReader extends SimulatorReader {
    public GenericUsbReader(Context context) {
        super(context);
    }
    
    public GenericUsbReader() {
        super(null);
    }
}
