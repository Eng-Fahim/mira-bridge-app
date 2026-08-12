package com.example.uhf.MiraRfid;

/**
 * واجهة موحدة لجميع أنواع قارئات RFID
 * أي قارئ جديد يجب أن ينفذ هذه الواجهة
 */
public interface MiraIRFIDReader {

    /**
     * أنواع القارئات المدعومة
     */
    enum ReaderType {
        CHAINWAY_C72("Chainway C72", "قارئ مدمج", true),
        CHAINWAY_C61("Chainway C61", "قارئ مدمج", true),
        ZEBRA_MC3300("Zebra MC3300", "قارئ مدمج", true),
        ZEBRA_RFD8500("Zebra RFD8500", "بلوتوث خارجي", false),
        HONEYWELL_EDA50("Honeywell EDA50", "قارئ مدمج", true),
        UNITECH_RP902("Unitech RP902", "قارئ مدمج", true),
        NORDIC_ID_NUR2("Nordic ID NUR2", "بلوتوث خارجي", false),
        TSL_1128("TSL 1128", "بلوتوث خارجي", false),
        GENERIC_BLUETOOTH("Bluetooth عام", "بلوتوث خارجي", false),
        SIMULATOR("محاكي", "بدون جهاز", false);

        public final String displayName;
        public final String description;
        public final boolean isBuiltIn;

        ReaderType(String displayName, String description, boolean isBuiltIn) {
            this.displayName = displayName;
            this.description = description;
            this.isBuiltIn = isBuiltIn;
        }

        public String getFullName() {
            return displayName + " - " + description;
        }
    }

    /**
     * دورة حياة القارئ
     */
    boolean initialize();
    boolean connect();
    void disconnect();
    void release();
    boolean isConnected();

    /**
     * عمليات المسح
     */
    boolean startScan();
    void stopScan();
    boolean isScanning();

    /**
     * الإعدادات
     */
    void setPower(int powerDbm);
    void setRssiThreshold(int threshold);
    void setScanInterval(int intervalMs);

    /**
     * معلومات الجهاز
     */
    String getDeviceName();
    String getFirmwareVersion();
    int getBatteryLevel();

    /**
     * المستمع
     */
    void setOnTagReadListener(OnTagReadListener listener);

    /**
     * واجهة المستمع لنتائج القراءة
     */
    interface OnTagReadListener {
        void onTagRead(String epc, int rssi);
        void onScanStarted();
        void onScanStopped();
        void onConnectionChanged(boolean connected);
        void onError(String error);
    }
}
