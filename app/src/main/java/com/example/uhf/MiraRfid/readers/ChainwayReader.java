package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ Chainway حقيقي - محسّن ومُحصّن ضد تسريب الذاكرة وأخطاء المزامنة
 * 
 * ✅ الإصدار 2.0 — تصحيحات جوهرية:
 * - setTagLockState: Lock Code صحيح لكل بنك (EPC Gen2 compliant)
 * - writeEpc: توحيد كلمة المرور الافتراضية
 * - writeData: حساب طول الكلمات الصحيح
 * - readData: حماية من null
 * - lockTag/unlockTag: استخدام Bank_EPC الصحيح
 */
public class ChainwayReader implements MiraIRFIDReader {
    
    private static final String TAG = "ChainwayReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";
    
    // ✅ كلمة المرور الافتراضية للشرائح الخام
    private static final String DEFAULT_PWD = "00000000";
    
    private Context context;
    private RFIDWithUHFUART reader;
    private OnTagReadListener listener;
    private volatile boolean isScanning = false;
    private volatile boolean isConnected = false;
    
    // إعدادات ديناميكية
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private int beepMode = 1;
    private int vibrationMode = 1;
    private int continuousMode = 1;
    private int session = 0;
    private int target = 0;
    private int qValue = 4;
    
    private ExecutorService scanExecutor;
    private SharedPreferences prefs;
    
    public ChainwayReader(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            loadSettings();
        }
    }

    private synchronized void ensureExecutor() {
        if (scanExecutor == null || scanExecutor.isShutdown() || scanExecutor.isTerminated()) {
            scanExecutor = Executors.newSingleThreadExecutor();
        }
    }
    
    private void loadSettings() {
        if (prefs == null) return;
        powerDbm = prefs.getInt("power_dbm", 28);
        rssiThreshold = prefs.getInt("rssi_threshold", -70);
        scanIntervalMs = prefs.getInt("scan_interval_ms", 200);
        beepMode = prefs.getInt("beep_mode", 1);
        vibrationMode = prefs.getInt("vibration_mode", 1);
        continuousMode = prefs.getInt("continuous_mode", 1);
        session = prefs.getInt("session", 0);
        target = prefs.getInt("target", 0);
        qValue = prefs.getInt("q_value", 4);
    }
    
    private void saveSettings() {
        if (prefs == null) return;
        prefs.edit()
            .putInt("power_dbm", powerDbm)
            .putInt("rssi_threshold", rssiThreshold)
            .putInt("scan_interval_ms", scanIntervalMs)
            .putInt("beep_mode", beepMode)
            .putInt("vibration_mode", vibrationMode)
            .putInt("continuous_mode", continuousMode)
            .putInt("session", session)
            .putInt("target", target)
            .putInt("q_value", qValue)
            .apply();
    }
    
    // =========================================================================
    // 🔧 دالة مساعدة: توحيد كلمة المرور
    // =========================================================================
    
    /**
     * ✅ توحيد كلمة المرور: null/empty → "00000000"
     * تقليم + تحويل لحالة أحرف كبيرة + إزالة المسافات
     */
    private String normalizePwd(String pwdStr) {
        if (pwdStr == null || pwdStr.trim().isEmpty()) {
            return DEFAULT_PWD;
        }
        return pwdStr.trim().toUpperCase();
    }
    
    // =========================================================================
    // 🔌 التهيئة والاتصال
    // =========================================================================
    
    @Override
    public boolean initialize() {
        if (context == null) return false;
        try {
            if (reader == null) {
                reader = RFIDWithUHFUART.getInstance();
            }
            boolean success = reader.init(context);
            
            if (success) {
                applySupportedSettings();
                Log.i(TAG, "✅ Chainway initialized successfully");
            } else {
                Log.e(TAG, "❌ Chainway init failed");
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Init error", e);
            return false;
        }
    }
    
    private void applySupportedSettings() {
        if (reader == null) return;
        try {
            reader.setPower(powerDbm);
        } catch (Exception e) {
            Log.e(TAG, "Error applying settings", e);
        }
    }
    
    @Override
    public boolean connect() {
        try {
            if (reader == null) {
                initialize();
            }
            isConnected = (reader != null);
            if (isConnected) {
                applySupportedSettings();
                if (listener != null) {
                    listener.onConnectionChanged(true);
                }
            }
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            return false;
        }
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) {
            listener.onConnectionChanged(false);
        }
    }
    
    @Override
    public synchronized void release() {
        disconnect();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        try {
            if (reader != null) {
                reader.free();
                reader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Release error", e);
        }
        listener = null;
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    // =========================================================================
    // 📡 المسح والقراءة
    // =========================================================================
    
    @Override
    public boolean startScan() {
        if (!com.example.uhf.activity.BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            Log.d(TAG, "RFID device not present - cannot start real scan");
            return false;
        }
        
        if (reader == null && !connect()) return false;
        if (isScanning) return true;
        
        try {
            applySupportedSettings();
            
            final boolean[] result = new boolean[1];
            Thread scanThread = new Thread(() -> {
                try {
                    result[0] = reader.startInventoryTag();
                } catch (Exception e) {
                    result[0] = false;
                }
            });
            scanThread.start();
            scanThread.join(3000);
            
            if (scanThread.isAlive()) {
                Log.e(TAG, "startInventoryTag timeout - device not responding");
                return false;
            }
            
            boolean success = result[0];
            
            if (success) {
                isScanning = true;
                if (listener != null) listener.onScanStarted();
                ensureExecutor();
                scanExecutor.execute(this::scanLoop);
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "startScan error", e);
            return false;
        }
    }
    
    private void scanLoop() {
        while (isScanning && !Thread.currentThread().isInterrupted()) {
            try {
                UHFTAGInfo tag;
                if (reader != null) {
                    while (isScanning && (tag = reader.readTagFromBuffer()) != null) {
                        processTag(tag);
                    }
                }
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Log.d(TAG, "Scan loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (isScanning && listener != null) {
                    listener.onError(e.getMessage());
                }
                Log.e(TAG, "Scan loop error", e);
                break;
            }
        }
    }
    
    private void processTag(UHFTAGInfo tag) {
        if (tag == null) return;
        try {
            String epc = tag.getEPC();
            String rssiStr = tag.getRssi();
            int rssi = parseRssi(rssiStr);
            
            if (rssi < rssiThreshold) {
                return;
            }
            
            if (epc != null && !epc.isEmpty() && listener != null) {
                listener.onTagRead(epc, rssi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Process tag error", e);
        }
    }
    
    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr.replace("dBm", "").replace("dbm", "").trim();
            int rssi = Integer.parseInt(cleaned);
            return rssi > 0 ? -rssi : rssi;
        } catch (NumberFormatException e) {
            return -50;
        }
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        try {
            if (reader != null) {
                reader.stopInventory();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop scan error", e);
        }
        
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
        }
        
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    // =========================================================================
    // ⚙️ الإعدادات
    // =========================================================================
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        saveSettings();
        try {
            if (reader != null) {
                reader.setPower(powerDbm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Set power error", e);
        }
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
        saveSettings();
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = Math.max(50, intervalMs);
        saveSettings();
    }
    
    public void setBeepMode(int mode) { this.beepMode = mode; saveSettings(); }
    public void setVibrationMode(int mode) { this.vibrationMode = mode; saveSettings(); }
    public void setContinuousMode(int mode) { this.continuousMode = mode; saveSettings(); }
    public void setSession(int session) { this.session = session; saveSettings(); }
    public void setTarget(int target) { this.target = target; saveSettings(); }
    public void setQValue(int qValue) { this.qValue = qValue; saveSettings(); }
    
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public int getBeepMode() { return beepMode; }
    public int getVibrationMode() { return vibrationMode; }
    public int getContinuousMode() { return continuousMode; }
    public int getSession() { return session; }
    public int getTarget() { return target; }
    public int getQValue() { return qValue; }
    
    @Override
    public String getDeviceName() { return "Chainway " + Build.MODEL + " / UHF Terminal"; }
    
    @Override
    public String getFirmwareVersion() {
        try {
            if (reader != null) return reader.getVersion();
        } catch (Exception ignored) {}
        return "Unknown";
    }
    
    @Override
    public int getBatteryLevel() { return -1; }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }

    // =========================================================================
    // 💾 الكتابة والقراءة في الذاكرة
    // =========================================================================

    /**
     * ✅ كتابة بيانات في بنك الذاكرة
     * 
     * @param pwdStr كلمة مرور الوصول (null → "00000000")
     * @param bank رقم البنك (0=RESERVED, 1=EPC, 2=TID, 3=USER)
     * @param ptr مؤشر الكلمة (Word pointer) — عادة 2 لـ EPC
     * @param data البيانات بصيغة Hex String
     */
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (reader == null || data == null || data.isEmpty()) {
            Log.w(TAG, "writeData: invalid args");
            return false;
        }
        
        String safePwd = normalizePwd(pwdStr);
        
        try {
            // ✅ حساب عدد الكلمات الصحيح: كل كلمة = 4 حروف Hex
            int hexLen = data.length();
            int wordCount = (hexLen + 3) / 4; // ceil division
            
            // ✅ إضافة Padding إذا لزم
            StringBuilder paddedData = new StringBuilder(data.toUpperCase());
            while (paddedData.length() < wordCount * 4) {
                paddedData.append("0");
            }
            
            Log.i(TAG, "📝 writeData: bank=" + bank + " ptr=" + ptr + 
                  " words=" + wordCount + " len=" + paddedData.length() + 
                  " data=" + paddedData);
            
            boolean result = reader.writeData(safePwd, bank, ptr, wordCount, paddedData.toString());
            
            if (result) {
                Log.i(TAG, "✅ writeData OK");
            } else {
                Log.e(TAG, "❌ writeData FAILED: bank=" + bank + " ptr=" + ptr);
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "writeData exception: bank=" + bank + " ptr=" + ptr, e);
            return false;
        }
    }

    /**
     * ✅ قراءة بيانات من بنك الذاكرة
     * 
     * @param pwdStr كلمة مرور الوصول (null → "00000000")
     * @param bank رقم البنك
     * @param ptr مؤشر الكلمة
     * @param len عدد الكلمات للقراءة
     */
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        if (reader == null) {
            Log.w(TAG, "readData: reader is null");
            return null;
        }
        
        String safePwd = normalizePwd(pwdStr);
        
        try {
            Log.d(TAG, "📖 readData: bank=" + bank + " ptr=" + ptr + " len=" + len);
            String result = reader.readData(safePwd, bank, ptr, len);
            
            if (result != null && !result.isEmpty()) {
                Log.d(TAG, "✅ readData OK: " + result);
            } else {
                Log.w(TAG, "⚠️ readData returned empty");
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "readData exception: bank=" + bank + " ptr=" + ptr, e);
            return null;
        }
    }

    // =========================================================================
    // 🔐 القفل والحماية (الجزء الأهم!)
    // =========================================================================

    /**
     * ✅ قفل/فتح بنك ذاكرة معين — مُصحَّح وفق EPC Gen2
     * 
     * Lock Code Format (EPC Gen2):
     * ┌─────────────────────────────────────────────┐
     * │ Bits:  [15-14] [13-12] [11-10] [9-8] [7-6] [5-4] [3-2] [1-0] │
     * │        Access  Kill    EPC     EPC   TID   TID   USER  USER │
     * │                                                              │
     * │ Value: 00=Open, 01=Write, 10=PermaWrite, 11=Lock             │
     * └─────────────────────────────────────────────┘
     * 
     * أمثلة عملية:
     * - "0C00" → قفل EPC (bits 11-10 = 11)
     * - "C000" → قفل Access + Kill
     * - "00C0" → قفل USER
     * - "0000" → فتح كل شيء
     * 
     * @param pwdStr كلمة مرور الوصول
     * @param bank رقم البنك (0=ACCESS, 1=EPC, 2=TID, 3=USER)
     * @param shouldLock true=قفل, false=فتح
     */
    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (reader == null) {
            Log.w(TAG, "setTagLockState: reader is null");
            return false;
        }
        
        String safePwd = normalizePwd(pwdStr);
        
        // ✅ بناء Lock Code الصحيح حسب البنك
        String lockCode;
        switch (bank) {
            case 0: // ACCESS Bank — قفل Access + Kill معاً
                lockCode = shouldLock ? "C000" : "0000";
                break;
            case 1: // EPC Bank — قفل EPC فقط
                lockCode = shouldLock ? "0C00" : "0000";
                break;
            case 2: // TID Bank — عادةً للقراءة فقط، لا يُقفل
                Log.w(TAG, "⚠️ TID Bank is read-only — cannot lock");
                return false;
            case 3: // USER Bank
                lockCode = shouldLock ? "00C0" : "0000";
                break;
            default:
                Log.e(TAG, "Unsupported bank: " + bank);
                return false;
        }
        
        try {
            Log.i(TAG, "🔐 setTagLockState: bank=" + bank + 
                  " lock=" + shouldLock + " code=" + lockCode + 
                  " pwd=" + safePwd);
            
            boolean result = reader.lockMem(safePwd, lockCode);
            
            if (result) {
                Log.i(TAG, "🔒 Lock operation successful: bank=" + bank + 
                      " locked=" + shouldLock);
            } else {
                Log.e(TAG, "❌ Lock operation FAILED: bank=" + bank + 
                      " code=" + lockCode);
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Lock exception: bank=" + bank + " code=" + lockCode, e);
            return false;
        }
    }

    // =========================================================================
    // 🎯 دوال EPC المخصصة
    // =========================================================================

    /**
     * ✅ كتابة EPC على الشريحة
     * EPC دائماً يبدأ من Word 2 (Bit 32) في Bank 1
     */
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (newEpc == null || newEpc.isEmpty()) {
            Log.w(TAG, "writeEpc: empty EPC");
            return false;
        }
        
        String safePwd = normalizePwd(accessPassword);
        Log.i(TAG, "📝 writeEpc: epc=" + newEpc + " pwd=" + safePwd);
        
        // ✅ EPC يبدأ دائماً من Word 2 في Bank 1
        return writeData(safePwd, RFIDWithUHFUART.Bank_EPC, 2, newEpc);
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return writeData(normalizePwd(accessPassword), 
                         RFIDWithUHFUART.Bank_USER, address, data);
    }
    
    @Override
public String readTid() {
    // ✅ محاولة قراءة TID بأطوال مختلفة
    for (int words : new int[]{6, 5, 4}) {
        try {
            String tid = readData(DEFAULT_PWD, RFIDWithUHFUART.Bank_TID, 0, words);
            if (tid != null && !tid.isEmpty()) {
                tid = tid.trim().toUpperCase();
                // ✅ إزالة padding
                while (tid.length() > 20 && tid.endsWith("0000")) {
                    tid = tid.substring(0, tid.length() - 4);
                }
                Log.i(TAG, "✅ TID read OK (" + words + " words): " + tid 
                    + " (length=" + tid.length() + ")");
                return tid;
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ TID read failed with " + words + " words", e);
        }
    }
    Log.e(TAG, "❌ TID read FAILED after 3 attempts");
    return null;
}
    
    @Override
    public String readUserMemory(int address, int length) {
        return readData(DEFAULT_PWD, RFIDWithUHFUART.Bank_USER, address, length);
    }
    
    /**
     * ✅ قفل الشريحة (EPC Bank فقط) — الأكثر استخداماً
     */
    @Override
    public boolean lockTag(String accessPassword) {
        return setTagLockState(normalizePwd(accessPassword), 
                               RFIDWithUHFUART.Bank_EPC, true);
    }
    
    @Override
    public boolean unlockTag(String accessPassword) {
        return setTagLockState(normalizePwd(accessPassword), 
                               RFIDWithUHFUART.Bank_EPC, false);
    }
    
    @Override
    public boolean killTag(String accessPassword) {
        if (reader == null) return false;
        String safePwd = normalizePwd(accessPassword);
        try {
            Log.w(TAG, "☠️ killTag — IRREVERSIBLE operation!");
            return reader.killTag(safePwd);
        } catch (Exception e) {
            Log.e(TAG, "killTag error", e);
            return false;
        }
    }

    // =========================================================================
    // 🎯 الفلترة
    // =========================================================================

    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        if (reader == null || filterData == null) return false;
        try {
            int len = filterData.length() * 4;
            Log.d(TAG, "🎯 setFilter: bank=" + bank + " ptr=" + ptr + 
                  " data=" + filterData);
            return reader.setFilter(bank, ptr, len, filterData);
        } catch (Exception e) {
            Log.e(TAG, "setFilter error", e);
            return false;
        }
    }

    @Override
    public void clearFilter() {
        if (reader != null) {
            try {
                reader.setFilter(1, 0, 0, "");
                Log.d(TAG, "🎯 clearFilter OK");
            } catch (Exception e) {
                Log.e(TAG, "Clear filter error", e);
            }
        }
    }
}
