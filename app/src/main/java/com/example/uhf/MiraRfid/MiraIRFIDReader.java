package com.example.uhf.MiraRfid;

/**
 * واجهة موحدة لجميع أنواع قارئات RFID
 */
public interface MiraIRFIDReader {

    public enum ReaderType {
    // ✅ القارئات المدمجة (Built-in)
    CHAINWAY_C72("Chainway C72", "قارئ مدمج - UHF", true),
    CHAINWAY_C61("Chainway C61", "قارئ مدمج - UHF", true),
    CHAINWAY_C66("Chainway C66", "قارئ مدمج - UHF", true),
    CHAINWAY_C71("Chainway C71", "قارئ مدمج - UHF", true),
    CHAINWAY_R6("Chainway R6", "قارئ مدمج - UHF", true),
    ZEBRA_MC3300("Zebra MC3300", "قارئ مدمج - UHF", true),
    HONEYWELL_EDA50("Honeywell EDA50", "قارئ مدمج - UHF", true),
    UNITECH_RP902("Unitech RP902", "قارئ مدمج - UHF", true),

    // ✅ القارئات الخارجية (External)
    GENERIC_BLUETOOTH("Bluetooth عام (SPP)", "بلوتوث خارجي - أي جهاز", false),

    // ✅ [جديد] قارئ VANCH الثابت عبر TCP/IP
    VANCH_FIXED("VANCH Fixed Reader", "قارئ ثابت عبر الشبكة (TCP/IP)", false),

    // ✅ المحاكي (للتطوير)
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
     * الإعدادات ومعلومات الجهاز
     */
    void setPower(int powerDbm);
    void setRssiThreshold(int threshold);
    void setScanInterval(int intervalMs);
    String getDeviceName();
    String getFirmwareVersion();
    int getBatteryLevel();

    /**
     * المستمع
     */
    void setOnTagReadListener(OnTagReadListener listener);

    /**
     * عمليات القراءة والكتابة المباشرة والفلترة
     */
    boolean writeData(String pwdStr, int bank, int ptr, String data);
    String readData(String pwdStr, int bank, int ptr, int len);
    boolean setTagLockState(String pwdStr, int bank, boolean shouldLock);
    boolean setFilter(int bank, int ptr, String filterData);
    void clearFilter();

    /**
     * عمليات إضافية
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
