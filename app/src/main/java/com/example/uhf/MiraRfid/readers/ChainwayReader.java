package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.utils.MiraFileLogger;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.example.uhf.MiraRfid.adaptive.AdaptiveConfigurator;
import com.example.uhf.MiraRfid.MiraReaderSettings;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ChainwayReader v4.1 — متوافق مع RFIDManager v4.0 + MiraReaderSettings v3.2
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغييرات الجوهرية:
 *
 *   [v4.0]
 *   1. ✅ initialize() — لا يدّعي الجاهزية عند العثور على shared reader.
 *      يتحقق فعلياً عبر getVersion() قبل القبول.
 *   2. ✅ initialize() — ينشر القارئ عبر publishSharedReader().
 *   3. ✅ applySupportedSettings() — فحص null صريح.
 *   4. ✅ release() — لا يُحرّر shared reader (يملكه RFIDManager).
 *
 *   [v4.1] ← جديد
 *   5. ✅ التسجيل كمستمع لتغيّرات MiraReaderSettings — أي تغيير في
 *      الإعدادات من أي شاشة يُطبَّق فوراً على القارئ (خاصة الجرد).
 *   6. ✅ applySettingsNow() — دالة عامة لاستدعائها من أي Fragment
 *      في onResume() لضمان تطبيق أحدث الإعدادات.
 *   7. ✅ throttle — منع تطبيق متكرر سريع.
 *   8. ✅ release() — إلغاء تسجيل المستمع لمنع التسريبات.
 */
public class ChainwayReader implements MiraIRFIDReader {

    private AdaptiveConfigurator adaptiveConfigurator;

    private static final String TAG = "ChainwayReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";

    private static final String DEFAULT_PWD = "00000000";

    // ═══════════════════════════════════════════════════════════════
    //  القيم الافتراضية المُحسّنة للمعادن (الذهب والمجوهرات)
    // ═══════════════════════════════════════════════════════════════
    private static final int DEFAULT_POWER_DBM       = 30;
    private static final int DEFAULT_RSSI_THRESHOLD  = -75;
    private static final int DEFAULT_SCAN_INTERVAL   = 100;
    private static final int DEFAULT_BEEP_MODE       = 1;
    private static final int DEFAULT_VIBRATION_MODE  = 1;
    private static final int DEFAULT_CONTINUOUS_MODE = 1;
    private static final int DEFAULT_SESSION         = 1;
    private static final int DEFAULT_TARGET          = 2;
    private static final int DEFAULT_Q_VALUE         = 4;
    private static final int DEFAULT_FREQUENCY_MODE  = 2;

    private Context context;
    private RFIDWithUHFUART reader;
    private OnTagReadListener listener;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private volatile boolean isConnected = false;

    // إعدادات ديناميكية
    private int powerDbm = DEFAULT_POWER_DBM;
    private int rssiThreshold = DEFAULT_RSSI_THRESHOLD;
    private int scanIntervalMs = DEFAULT_SCAN_INTERVAL;
    private int beepMode = DEFAULT_BEEP_MODE;
    private int vibrationMode = DEFAULT_VIBRATION_MODE;
    private int continuousMode = DEFAULT_CONTINUOUS_MODE;
    private int session = DEFAULT_SESSION;
    private int target = DEFAULT_TARGET;
    private int qValue = DEFAULT_Q_VALUE;
    private int frequencyMode = DEFAULT_FREQUENCY_MODE;

    private ExecutorService scanExecutor;
    private SharedPreferences prefs;

    // 🆕 v4.1 — مستمع تغييرات الإعدادات + throttle
    private MiraReaderSettings.OnSettingsChangedListener settingsListener;
    private volatile long lastApplyTime = 0;
    private static final long MIN_APPLY_INTERVAL_MS = 300;

    // ═══════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════
    public ChainwayReader(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            this.adaptiveConfigurator = new AdaptiveConfigurator(this.context);
            this.adaptiveConfigurator.initializeReader();
            loadSettings();

            // 🆕 v4.1 — التسجيل كمستمع لتغيّرات MiraReaderSettings
            settingsListener = () -> {
                Log.i(TAG, "🔔 Settings changed → re-applying to reader");
                applySettingsNow();
            };
            MiraReaderSettings.getInstance(this.context).addListener(settingsListener);
            Log.i(TAG, "✅ ChainwayReader v4.1 ready (listener registered)");
        }
    }

    private synchronized void ensureExecutor() {
        if (scanExecutor == null || scanExecutor.isShutdown()
                || scanExecutor.isTerminated()) {
            scanExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Chainway-ScanLoop");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private void loadSettings() {
        if (prefs == null) return;
        powerDbm       = prefs.getInt("power_dbm",       DEFAULT_POWER_DBM);
        rssiThreshold  = prefs.getInt("rssi_threshold",  DEFAULT_RSSI_THRESHOLD);
        scanIntervalMs = prefs.getInt("scan_interval_ms", DEFAULT_SCAN_INTERVAL);
        beepMode       = prefs.getInt("beep_mode",       DEFAULT_BEEP_MODE);
        vibrationMode  = prefs.getInt("vibration_mode",  DEFAULT_VIBRATION_MODE);
        continuousMode = prefs.getInt("continuous_mode", DEFAULT_CONTINUOUS_MODE);
        session        = prefs.getInt("session",         DEFAULT_SESSION);
        target         = prefs.getInt("target",          DEFAULT_TARGET);
        qValue         = prefs.getInt("q_value",         DEFAULT_Q_VALUE);
        frequencyMode  = prefs.getInt("frequency_mode",  DEFAULT_FREQUENCY_MODE);
    }

    private void saveSettings() {
        if (prefs == null) return;
        prefs.edit()
                .putInt("power_dbm",        powerDbm)
                .putInt("rssi_threshold",   rssiThreshold)
                .putInt("scan_interval_ms", scanIntervalMs)
                .putInt("beep_mode",        beepMode)
                .putInt("vibration_mode",   vibrationMode)
                .putInt("continuous_mode",  continuousMode)
                .putInt("session",          session)
                .putInt("target",           target)
                .putInt("q_value",          qValue)
                .putInt("frequency_mode",   frequencyMode)
                .apply();
    }

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
        if (context == null) {
            Log.e(TAG, "❌ Context is null");
            return false;
        }

        try {
            // ═══════════════════════════════════════════════════════
            // 1) هل يوجد shared reader مُتحقَّق منه؟
            // ═══════════════════════════════════════════════════════
            RFIDWithUHFUART sharedReader = BaseTabFragmentActivity.getSharedReader();
            if (sharedReader != null && verifySharedReader(sharedReader)) {
                Log.i(TAG, "✅ Using VERIFIED shared reader instance");
                this.reader = sharedReader;
                this.isConnected = true;
                applySupportedSettings();
                return true;
            }

            // ═══════════════════════════════════════════════════════
            // 2) تهيئة قارئ جديد
            // ═══════════════════════════════════════════════════════
            if (reader == null) {
                reader = RFIDWithUHFUART.getInstance();
            }

            if (reader == null) {
                Log.e(TAG, "❌ RFIDWithUHFUART.getInstance() returned null");
                return false;
            }

            // ✅ init مع timeout
            final boolean[] initResult = new boolean[1];
            final Object initLock = new Object();

            Thread initThread = new Thread(() -> {
                try {
                    initResult[0] = reader.init(context);
                } catch (Throwable e) {
                    Log.e(TAG, "init thread error", e);
                    initResult[0] = false;
                } finally {
                    synchronized (initLock) {
                        initLock.notifyAll();
                    }
                }
            }, "Chainway-Init");
            initThread.setDaemon(true);
            initThread.start();

            synchronized (initLock) {
                try {
                    initLock.wait(8000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (initThread.isAlive()) {
                Log.e(TAG, "❌ Init timeout (8000ms)");
                return false;
            }

            boolean success = initResult[0];

            if (success) {
                isConnected = true;

                // ✅ انشر القارئ للاستخدام المشترك
                BaseTabFragmentActivity.publishSharedReader(reader);
                Log.i(TAG, "📢 Reader published to shared slot");

                applySupportedSettings();
                Log.i(TAG, "✅ Chainway initialized successfully");
            } else {
                isConnected = false;
                Log.e(TAG, "❌ Chainway init failed");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "❌ Init exception", e);
            isConnected = false;
            return false;
        }
    }

    /**
     * ✅ يتحقق من أن shared reader يعمل فعلاً قبل قبوله.
     */
    private boolean verifySharedReader(RFIDWithUHFUART sdk) {
        if (sdk == null) return false;
        try {
            String ver = sdk.getVersion();
            boolean ok = (ver != null && !ver.isEmpty());
            if (ok) {
                Log.d(TAG, "   └─ Shared reader version: " + ver);
            } else {
                Log.w(TAG, "   └─ Shared reader returned empty version");
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "   └─ Shared reader unresponsive: " + e.getMessage());
            return false;
        }
    }

    /**
     * ✅ إعدادات خفيفة — الطاقة فقط. سريعة جداً.
     */
    private void applySupportedSettings() {
        if (reader == null || context == null) return;
        try {
            MiraReaderSettings settings = MiraReaderSettings.getInstance(context);
            if (settings == null) return;

            int power = settings.getPowerDbm();
            reader.setPower(power);

            MiraFileLogger.log(TAG, "⚡ Power applied: " + power + " dBm");
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "applySupportedSettings error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🆕 v4.1 — الدالة العامة لتطبيق الإعدادات فوراً
    // ═══════════════════════════════════════════════════════════════
    /**
     * ✅ يقرأ أحدث إعدادات من MiraReaderSettings ويطبّقها على القارئ.
     *
     * استخدمه في:
     *   - onResume() لكل Fragment يستخدم القارئ (خاصة الجرد)
     *   - بعد أي تغيير إعدادات برمجي
     *   - قبل بدء المسح في أي وضع
     *
     * @return true إذا تم التطبيق بنجاح
     */
    public boolean applySettingsNow() {
        // throttle — منع الاستدعاء المتكرر السريع
        long now = System.currentTimeMillis();
        if (now - lastApplyTime < MIN_APPLY_INTERVAL_MS) {
            Log.d(TAG, "applySettingsNow throttled (Δ=" + (now - lastApplyTime) + "ms)");
            return true;
        }
        lastApplyTime = now;

        if (reader == null || !isConnected || context == null) {
            Log.w(TAG, "applySettingsNow: not ready (reader=" + (reader != null)
                    + " connected=" + isConnected + ")");
            return false;
        }

        try {
            MiraReaderSettings settings = MiraReaderSettings.getInstance(context);
            // نُحدّث مرجعنا الداخلي أيضاً
            loadSettings();
            MiraReaderSettings.ApplyResult result = settings.applyToReader(reader);
            Log.i(TAG, "✅ applySettingsNow: " + result);

            // إذا كان المسح نشطاً، لا نُوقفه — الإعدادات طُبّقت ديناميكياً
            return result.isSuccess();
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "applySettingsNow error", e);
            return false;
        }
    }

    /**
     * ✅ استدعاء دالة SDK مع int عبر Reflection
     */
    private void invokeIntMethod(String methodName, int value) {
        if (reader == null) return;
        try {
            Method m = reader.getClass().getMethod(methodName, int.class);
            m.invoke(reader, value);
            MiraFileLogger.log(TAG, "   ✅ " + methodName + "(" + value + ")");
        } catch (NoSuchMethodException e) {
            MiraFileLogger.logWarn(TAG, "   ⚠️ " + methodName + " NOT FOUND in SDK");
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "   ❌ " + methodName + " error: " + e.getMessage());
        }
    }

    /**
     * ✅ استدعاء دالة SDK مع boolean عبر Reflection
     */
    private void invokeBooleanMethod(String methodName, boolean value) {
        if (reader == null) return;
        try {
            Method m = reader.getClass().getMethod(methodName, boolean.class);
            m.invoke(reader, value);
            Log.d(TAG, "   ✅ " + methodName + "(" + value + ")");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "   ⚠️ " + methodName + " NOT FOUND in SDK");
        } catch (Exception e) {
            Log.e(TAG, "   ❌ " + methodName + " error: " + e.getMessage());
        }
    }

    @Override
    public boolean connect() {
        try {
            if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
                Log.e(TAG, "❌ RFID device not present");
                return false;
            }

            if (reader == null) {
                if (!initialize()) return false;
            }

            isConnected = (reader != null);

            if (isConnected) {
                // 🆕 v4.1 — استخدام الدالة الموحّدة (مع reset للـ throttle)
                lastApplyTime = 0;
                applySettingsNow();

                if (listener != null) {
                    listener.onConnectionChanged(true);
                }
                Log.i(TAG, "✅ Connected + settings applied");
            }

            return isConnected;

        } catch (Exception e) {
            Log.e(TAG, "❌ Connection error", e);
            isConnected = false;
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

        // 🆕 v4.1 — إلغاء تسجيل المستمع
        if (settingsListener != null && context != null) {
            try {
                MiraReaderSettings.getInstance(context).removeListener(settingsListener);
                Log.d(TAG, "🔕 Settings listener unregistered");
            } catch (Exception ignored) {}
            settingsListener = null;
        }

        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }

        // ✅ لا نُحرّر shared reader — RFIDManager يملكه
        if (reader != null && reader != BaseTabFragmentActivity.getSharedReader()) {
            try {
                reader.free();
                Log.d(TAG, "🔓 Reader freed (private instance)");
            } catch (Exception e) {
                Log.e(TAG, "Release error", e);
            }
        } else if (reader != null) {
            Log.d(TAG, "⏭️ Skipping free() — reader is the shared instance");
        }

        reader = null;
        listener = null;
    }

    @Override
    public boolean isConnected() {
        return isConnected && reader != null;
    }

    // =========================================================================
    // 📡 المسح
    // =========================================================================

    @Override
    public boolean startScan() {
        MiraFileLogger.log(TAG, "🚀 startScan() called");

        // 1) تأكد من وجود العتاد
        if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
            Log.e(TAG, "❌ RFID device not present");
            if (listener != null) listener.onError("RFID device not present");
            return false;
        }

        // 2) منع التشغيل المزدوج
        if (!isScanning.compareAndSet(false, true)) {
            Log.d(TAG, "Already scanning");
            return true;
        }

        try {
            // 3) الاتصال إن لزم
            if (reader == null || !isConnected) {
                if (!connect()) {
                    Log.e(TAG, "❌ Cannot connect");
                    isScanning.set(false);
                    if (listener != null) listener.onError("Connection failed");
                    return false;
                }
            }

            // 4) 🆕 v4.1 — إعادة تطبيق الإعدادات (بدل applyAdvancedSettings)
            if (!applySettingsNow()) {
                Log.w(TAG, "⚠️ applySettingsNow failed — continuing anyway");
            }

            // 5) تفرّع حسب وضع القراءة
            MiraReaderSettings s = MiraReaderSettings.getInstance(context);
            boolean success;

            if (s.getReadMode() == MiraReaderSettings.READ_MODE_SINGLE) {
                Log.i(TAG, "🎯 Starting in SINGLE mode");
                success = startSingleTagLoop();
            } else {
                Log.i(TAG, "🔄 Starting in AUTO mode");
                success = startAutoInventory();
            }

            // 6) التحقق
            if (!success) {
                Log.e(TAG, "❌ Failed to start inventory");
                isScanning.set(false);
                if (listener != null) listener.onError("Failed to start inventory");
                return false;
            }

            // 7) أبلغ المستمع
            if (listener != null) listener.onScanStarted();

            Log.i(TAG, "✅ Scan started successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ startScan exception", e);
            isScanning.set(false);
            if (listener != null) listener.onError("Start error: " + e.getMessage());
            return false;
        }
    }

    private boolean startAutoInventory() {
        try {
            boolean ok = reader.startInventoryTag();
            if (ok) {
                ensureExecutor();
                scanExecutor.execute(this::scanLoop);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "startAutoInventory error", e);
            return false;
        }
    }

    private boolean startSingleTagLoop() {
        try {
            ensureExecutor();
            scanExecutor.execute(() -> {
                Log.d(TAG, "🎯 Single tag loop started");
                while (isScanning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        UHFTAGInfo tag = reader.inventorySingleTag();
                        if (tag != null && tag.getEPC() != null
                                && !tag.getEPC().isEmpty()) {
                            processTag(tag);
                        }
                        Thread.sleep(30);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "single tag loop error", e);
                        try { Thread.sleep(100); }
                        catch (InterruptedException ie) { break; }
                    }
                }
                Log.d(TAG, "🎯 Single tag loop ended");
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startSingleTagLoop error", e);
            return false;
        }
    }

    private void scanLoop() {
        Log.d(TAG, "🔄 Scan loop started");

        while (isScanning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                UHFTAGInfo tag;
                int tagsRead = 0;

                if (reader != null) {
                    while (isScanning.get() && (tag = reader.readTagFromBuffer()) != null) {
                        processTag(tag);
                        tagsRead++;

                        if (tagsRead % 50 == 0) {
                            Thread.sleep(1);
                        }
                    }
                }

                Thread.sleep(scanIntervalMs);

            } catch (InterruptedException e) {
                Log.d(TAG, "Scan loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Scan loop error: " + e.getMessage(), e);
                if (isScanning.get() && listener != null) {
                    listener.onError(e.getMessage());
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Log.d(TAG, "🔄 Scan loop ended");
    }

    private void processTag(UHFTAGInfo tag) {
        if (tag == null) return;
        try {
            String epc = tag.getEPC();
            int rssi = parseRssi(tag.getRssi());

            if (rssi < rssiThreshold) return;

            adaptiveConfigurator.updateLastTag(epc, rssi);
            adaptiveConfigurator.recordSuccess("read", "auto");

            MiraFileLogger.log(TAG, "📡 Tag read: EPC=" + epc + " RSSI=" + rssi);

            if (epc != null && !epc.isEmpty() && listener != null) {
                listener.onTagRead(epc, rssi);
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "processTag error", e);
        }
    }

    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr.replace("dBm", "")
                    .replace("dbm", "")
                    .replace("dB", "")
                    .trim();
            int rssi = Integer.parseInt(cleaned);
            return rssi > 0 ? -rssi : rssi;
        } catch (NumberFormatException e) {
            return -50;
        }
    }

    @Override
    public void stopScan() {
        if (!isScanning.getAndSet(false)) {
            MiraFileLogger.log(TAG, "⏹️ stopScan() skipped — already stopped");
            return;
        }

        MiraFileLogger.log(TAG, "⏹️ stopScan() executing...");

        try {
            if (reader != null) {
                reader.stopInventory();
                MiraFileLogger.log(TAG, "   └─ reader.stopInventory() OK");
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "   └─ reader.stopInventory() error: " + e.getMessage());
        }

        if (listener != null) {
            try {
                listener.onScanStopped();
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "onScanStopped callback error", e);
            }
        }
    }

    @Override
    public boolean isScanning() {
        return isScanning.get();
    }

    // =========================================================================
    // ⚙️ الإعدادات
    // =========================================================================

    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        saveSettings();
        try {
            if (reader != null && isConnected) {
                reader.setPower(powerDbm);
                Log.d(TAG, "⚡ Power set to: " + powerDbm + " dBm");
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

    public void setBeepMode(int mode) {
        this.beepMode = mode;
        saveSettings();
        invokeIntMethod("setBeepMode", mode);
    }

    public void setVibrationMode(int mode) {
        this.vibrationMode = mode;
        saveSettings();
        invokeIntMethod("setVibrationMode", mode);
    }

    public void setContinuousMode(int mode) {
        this.continuousMode = mode;
        saveSettings();
        invokeIntMethod("setContinuousMode", mode);
    }

    public void setSession(int session) {
        this.session = session;
        saveSettings();
        invokeIntMethod("setSession", session);
    }

    public void setTarget(int target) {
        this.target = target;
        saveSettings();
        invokeIntMethod("setTarget", target);
    }

    public void setQValue(int qValue) {
        this.qValue = qValue;
        saveSettings();
        invokeIntMethod("setQValue", qValue);
    }

    public void setFrequencyMode(int mode) {
        this.frequencyMode = mode;
        saveSettings();
        invokeIntMethod("setFrequencyMode", mode);
    }

    // ==================== Getters ====================
    public int getPowerDbm()       { return powerDbm; }
    public int getRssiThreshold()  { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public int getBeepMode()       { return beepMode; }
    public int getVibrationMode()  { return vibrationMode; }
    public int getContinuousMode() { return continuousMode; }
    public int getSession()        { return session; }
    public int getTarget()         { return target; }
    public int getQValue()         { return qValue; }
    public int getFrequencyMode()  { return frequencyMode; }

    @Override
    public String getDeviceName() {
        return "Chainway " + Build.MODEL + " / UHF Terminal";
    }

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
    // 💾 الكتابة والقراءة
    // =========================================================================

    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (reader == null || data == null || data.isEmpty()) {
            Log.w(TAG, "writeData: invalid args");
            return false;
        }

        String safePwd = normalizePwd(pwdStr);

        try {
            int hexLen = data.length();
            int wordCount = (hexLen + 3) / 4;

            StringBuilder paddedData = new StringBuilder(data.toUpperCase());
            while (paddedData.length() < wordCount * 4) {
                paddedData.append("0");
            }

            Log.i(TAG, "📝 writeData: bank=" + bank + " ptr=" + ptr
                    + " words=" + wordCount);

            boolean result = reader.writeData(safePwd, bank, ptr, wordCount,
                    paddedData.toString());

            if (result) {
                Log.i(TAG, "✅ writeData OK");
            } else {
                Log.e(TAG, "❌ writeData FAILED");
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "writeData exception", e);
            return false;
        }
    }

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
            Log.e(TAG, "readData exception", e);
            return null;
        }
    }

    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (reader == null) return false;

        String safePwd = normalizePwd(pwdStr);

        String lockCode;
        switch (bank) {
            case 0: lockCode = shouldLock ? "C000" : "0000"; break;
            case 1: lockCode = shouldLock ? "0C00" : "0000"; break;
            case 2:
                Log.w(TAG, "⚠️ TID Bank is read-only");
                return false;
            case 3: lockCode = shouldLock ? "00C0" : "0000"; break;
            default: return false;
        }

        try {
            Log.i(TAG, "🔐 setTagLockState: bank=" + bank + " code=" + lockCode);
            boolean result = reader.lockMem(safePwd, lockCode);
            if (result) {
                Log.i(TAG, "🔒 Lock OK");
            } else {
                Log.e(TAG, "❌ Lock FAILED");
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Lock exception", e);
            return false;
        }
    }

    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (newEpc == null || newEpc.isEmpty()) {
            Log.w(TAG, "writeEpc: empty EPC");
            return false;
        }
        String safePwd = normalizePwd(accessPassword);
        return writeData(safePwd, RFIDWithUHFUART.Bank_EPC, 2, newEpc);
    }

    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return writeData(normalizePwd(accessPassword),
                RFIDWithUHFUART.Bank_USER, address, data);
    }

    @Override
    public String readTid() {
        for (int words : new int[]{6, 5, 4}) {
            try {
                String tid = readData(DEFAULT_PWD, RFIDWithUHFUART.Bank_TID, 0, words);
                if (tid != null && !tid.isEmpty()) {
                    tid = tid.trim().toUpperCase();
                    while (tid.length() > 20 && tid.endsWith("0000")) {
                        tid = tid.substring(0, tid.length() - 4);
                    }
                    Log.i(TAG, "✅ TID: " + tid);
                    return tid;
                }
            } catch (Exception e) {
                Log.w(TAG, "TID read failed with " + words + " words");
            }
        }
        Log.e(TAG, "❌ TID read FAILED");
        return null;
    }

    @Override
    public String readUserMemory(int address, int length) {
        return readData(DEFAULT_PWD, RFIDWithUHFUART.Bank_USER, address, length);
    }

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
            Log.w(TAG, "☠️ killTag!");
            return reader.killTag(safePwd);
        } catch (Exception e) {
            Log.e(TAG, "killTag error", e);
            return false;
        }
    }

    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        if (reader == null || filterData == null) return false;
        try {
            int len = filterData.length() * 4;
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
            } catch (Exception e) {
                Log.e(TAG, "Clear filter error", e);
            }
        }
    }
    }
