package com.example.uhf.MiraRfid;

/**
 * واجهة موحدة لجميع أنواع قارئات RFID
 */
public interface MiraIRFIDReader {

    enum ReaderType {
        CHAINWAY_C72("Chainway C72", "قارئ مدمج - UHF", true),
        CHAINWAY_C61("Chainway C61", "قارئ مدمج - UHF", true),
        CHAINWAY_C66("Chainway C66", "قارئ مدمج - UHF", true),
        CHAINWAY_C71("Chainway C71", "قارئ مدمج - UHF", true),
        
        ZEBRA_MC3300("Zebra MC3300", "قارئ مدمج - UHF", true),
        ZEBRA_MC3390("Zebra MC3390", "قارئ مدمج - UHF", true),
        ZEBRA_RFD8500("Zebra RFD8500", "بلوتوث خارجي - UHF", false),
        ZEBRA_RFD40("Zebra RFD40", "بلوتوث خارجي - UHF", false),
        
        HONEYWELL_EDA50("Honeywell EDA50", "قارئ مدمج - UHF", true),
        HONEYWELL_EDA51("Honeywell EDA51", "قارئ مدمج - UHF", true),
        HONEYWELL_CT40("Honeywell CT40", "قارئ مدمج - UHF", true),
        HONEYWELL_CK65("Honeywell CK65", "قارئ مدمج - UHF", true),
        
        UNITECH_RP902("Unitech RP902", "قارئ مدمج - UHF", true),
        UNITECH_RP905("Unitech RP905", "قارئ مدمج - UHF", true),
        UNITECH_HT730("Unitech HT730", "قارئ مدمج - UHF", true),
        
        DATALOGIC_MEMOR10("Datalogic Memor 10", "قارئ مدمج - UHF", true),
        DATALOGIC_SKORPIO_X5("Datalogic Skorpio X5", "قارئ مدمج - UHF", true),
        
        BLUEBIRD_HF550("Bluebird HF550", "قارئ مدمج - UHF", true),
        BLUEBIRD_EF500("Bluebird EF500", "قارئ مدمج - UHF", true),
        
        POINT_MOBILE_PM67("Point Mobile PM67", "قارئ مدمج - UHF", true),
        POINT_MOBILE_PM85("Point Mobile PM85", "قارئ مدمج - UHF", true),
        
        IMPINJ_R420("Impinj R420", "قارئ ثابت - شبكة", false),
        IMPINJ_R700("Impinj R700", "قارئ ثابت - شبكة", false),
        IMPINJ_RS1000("Impinj RS1000", "قارئ محمول - Bluetooth", false),
        
        NORDIC_ID_NUR2("Nordic ID NUR2", "بلوتوث خارجي", false),
        NORDIC_ID_EXA51("Nordic ID EXA51", "قارئ مدمج", true),
        
        TSL_1128("TSL 1128", "بلوتوث خارجي", false),
        TSL_2128("TSL 2128", "بلوتوث خارجي", false),
        TSL_3166("TSL 3166", "بلوتوث خارجي", false),
        
        CIPHERLAB_RK25("CipherLab RK25", "قارئ مدمج", true),
        CIPHERLAB_RS35("CipherLab RS35", "قارئ مدمج", true),
        
        NEWLAND_N5S("Newland N5S", "قارئ مدمج", true),
        NEWLAND_N7("Newland N7", "قارئ مدمج", true),
        
        GENERIC_BLUETOOTH("Bluetooth عام (SPP)", "بلوتوث خارجي - أي جهاز", false),
        GENERIC_USB("USB عام", "USB - أي جهاز", false),
        GENERIC_NETWORK("شبكة TCP عامة", "IP:Port - أي جهاز", false),
        SIMULATOR("محاكي", "بدون جهاز - للاختبار", false);

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
     * عمليات الكتابة والقراءة المباشرة على البنكات (Low-Level Bank Access)
     */
    boolean writeData(String pwdStr, int bank, int ptr, String data);
    String readData(String pwdStr, int bank, int ptr, int len);
    boolean setTagLockState(String pwdStr, int bank, boolean shouldLock);
    void clearFilter();

    /**
     * عمليات الكتابة المتقدمة
     */
    boolean writeEpc(String newEpc, String accessPassword);
    boolean writeUserMemory(int address, String data, String accessPassword);
    String readTid();
    String readUserMemory(int address, int length);
    boolean lockTag(String accessPassword);
    boolean unlockTag(String accessPassword);
    boolean killTag(String accessPassword);

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
