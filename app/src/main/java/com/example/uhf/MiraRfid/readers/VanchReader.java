package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.utils.MiraFileLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  VanchReader v1.0 — قارئ ثابت VANCH عبر TCP/IP
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  الموديلات المدعومة: VF-747P، VF-747، وما شابه من قارئات VANCH الثابتة
 *
 *  المزايا:
 *  ✅ اتصال TCP Socket مع إعادة محاولة تلقائية
 *  ✅ خيط استقبال منفصل (Receiver Thread)
 *  ✅ تحليل تلقائي للوسوم المقروءة
 *  ✅ دعم كامل لواجهة MiraIRFIDReader
 *  ✅ يستخدم MiraFileLogger للتسجيل
 *  ✅ إدارة ذرّية للحالة (Atomic)
 *
 *  المتطلبات قبل الاستخدام:
 *  1. القارئ موصول على نفس الشبكة (WiFi/Ethernet)
 *  2. معرفة IP القارئ والبورت (عادة 4001)
 *  3. الكيبل والهوائي موصولان
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class VanchReader implements MiraIRFIDReader {

    private static final String TAG = "VanchReader";
    private static final String PREFS_NAME = "mira_vanch_settings";

    // ==================== الإعدادات الافتراضية ====================

    private static final int    DEFAULT_PORT           = 4001;
    private static final int    CONNECT_TIMEOUT_MS     = 5000;
    private static final int    READ_TIMEOUT_MS        = 500;
    private static final int    RECONNECT_DELAY_MS     = 3000;
    private static final int    MAX_RECONNECT_ATTEMPTS = 3;

    private static final int    DEFAULT_POWER_DBM      = 30;
    private static final int    DEFAULT_RSSI_THRESHOLD = -75;
    private static final int    DEFAULT_SCAN_INTERVAL  = 100;
    private static final byte   DEFAULT_REGION         = VanchProtocol.REGION_ETSI;

    // ==================== الحقول ====================

    private final Context context;
    private final SharedPreferences prefs;

    private String  host;               // IP القارئ
    private int     port = DEFAULT_PORT;

    private Socket              socket;
    private InputStream         inputStream;
    private OutputStream        outputStream;

    private OnTagReadListener   listener;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isScanning  = new AtomicBoolean(false);
    private final AtomicBoolean isReleased  = new AtomicBoolean(false);

    private ExecutorService rxExecutor;
    private Thread          reconnectThread;
    private volatile boolean shouldReconnect = true;

    // إعدادات ديناميكية
    private int powerDbm       = DEFAULT_POWER_DBM;
    private int rssiThreshold  = DEFAULT_RSSI_THRESHOLD;
    private int scanIntervalMs = DEFAULT_SCAN_INTERVAL;
    private byte regionCode    = DEFAULT_REGION;
    private int session        = 1;
    private int target         = 2;
    private int qValue         = 4;

    // إحصائيات
    private long totalTagsRead = 0;
    private long lastTagTime   = 0;

    // ==================== Constructor ====================

    public VanchReader(Context context, String host, int port) {
    if (context == null) {
        throw new IllegalArgumentException("VanchReader: context cannot be null");
    }
    this.context = context.getApplicationContext();
    this.prefs   = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

    // ✅ 1. حمّل القيم المحفوظة أولاً
    loadSettings();

    // ✅ 2. ثم طبّق القيم المُمرَّرة صراحةً (لها الأولوية)
    if (host != null && !host.isEmpty()) {
        this.host = host;
    }
    if (port > 0) {
        this.port = port;
    }

    MiraFileLogger.log(TAG, "🚀 VanchReader created for " + this.host + ":" + this.port);
}

    // ==================== الإعدادات ====================

    private void loadSettings() {
        try {
            this.host            = prefs.getString("host", host != null ? host : "192.168.1.100");
            this.port            = prefs.getInt("port", DEFAULT_PORT);
            this.powerDbm        = prefs.getInt("power_dbm", DEFAULT_POWER_DBM);
            this.rssiThreshold   = prefs.getInt("rssi_threshold", DEFAULT_RSSI_THRESHOLD);
            this.scanIntervalMs  = prefs.getInt("scan_interval_ms", DEFAULT_SCAN_INTERVAL);
            this.regionCode      = (byte) prefs.getInt("region_code", DEFAULT_REGION);
            this.session         = prefs.getInt("session", 1);
            this.target          = prefs.getInt("target", 2);
            this.qValue          = prefs.getInt("q_value", 4);

            MiraFileLogger.log(TAG, "📚 Settings loaded: " + host + ":" + port
                    + " power=" + powerDbm + " region=" + regionCode);
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "loadSettings error", e);
        }
    }

    private void saveSettings() {
        try {
            prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putInt("power_dbm", powerDbm)
                .putInt("rssi_threshold", rssiThreshold)
                .putInt("scan_interval_ms", scanIntervalMs)
                .putInt("region_code", regionCode)
                .putInt("session", session)
                .putInt("target", target)
                .putInt("q_value", qValue)
                .apply();
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "saveSettings error", e);
        }
    }

    private String loadSavedHost() {
        return prefs.getString("host", "192.168.1.100");
    }

    // ==================== دورة الحياة ====================

    @Override
    public boolean initialize() {
        // لا شيء للتهيئة — TCP لا يحتاج SDK
        MiraFileLogger.log(TAG, "✅ VanchReader initialized (no SDK required)");
        return true;
    }

    @Override
    public boolean connect() {
        if (isConnected.get()) {
            MiraFileLogger.log(TAG, "ℹ️ Already connected");
            return true;
        }

        try {
            MiraFileLogger.log(TAG, "🔌 Connecting to " + host + ":" + port + "...");

            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

            inputStream  = socket.getInputStream();
            outputStream = socket.getOutputStream();

            isConnected.set(true);
            shouldReconnect = true;

            // ✅ تهيئة الاتصال: ضبط Region + Power
            sendRegionCommand();
            sendPowerCommand();

            // ✅ بدء خيط الاستقبال
            ensureRxExecutor();

            if (listener != null) {
                listener.onConnectionChanged(true);
            }

            MiraFileLogger.log(TAG, "✅ Connected to Vanch reader: " + host + ":" + port);
            return true;

        } catch (IOException e) {
            MiraFileLogger.logError(TAG, "❌ Connection failed: " + e.getMessage());
            isConnected.set(false);
            cleanupSocket();
            return false;
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "❌ Connection exception", e);
            isConnected.set(false);
            cleanupSocket();
            return false;
        }
    }

    @Override
    public void disconnect() {
        MiraFileLogger.log(TAG, "🔌 Disconnecting...");

        shouldReconnect = false;
        stopScan();
        isConnected.set(false);
        cleanupSocket();

        if (listener != null) {
            listener.onConnectionChanged(false);
        }
    }

    @Override
    public synchronized void release() {
        if (isReleased.getAndSet(true)) return;
        MiraFileLogger.log(TAG, "🗑️ Releasing VanchReader...");

        disconnect();

        if (rxExecutor != null && !rxExecutor.isShutdown()) {
            rxExecutor.shutdownNow();
            rxExecutor = null;
        }

        if (reconnectThread != null && reconnectThread.isAlive()) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }

        listener = null;
    }

    @Override
    public boolean isConnected() {
        return isConnected.get() && socket != null && socket.isConnected();
    }

    // ==================== المسح ====================

    @Override
    public boolean startScan() {
        MiraFileLogger.log(TAG, "🚀 startScan() called");

        if (!isConnected()) {
            MiraFileLogger.logWarn(TAG, "⚠️ Not connected — attempting to connect");
            if (!connect()) {
                if (listener != null) listener.onError("Connection failed");
                return false;
            }
        }

        if (!isScanning.compareAndSet(false, true)) {
            MiraFileLogger.log(TAG, "ℹ️ Already scanning");
            return true;
        }

        try {
            byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_INVENTORY, null);
            if (frame == null || !sendFrame(frame)) {
                isScanning.set(false);
                if (listener != null) listener.onError("Failed to send INVENTORY command");
                return false;
            }

            if (listener != null) listener.onScanStarted();
            MiraFileLogger.log(TAG, "✅ INVENTORY command sent");
            return true;

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startScan error", e);
            isScanning.set(false);
            if (listener != null) listener.onError("Start error: " + e.getMessage());
            return false;
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
            byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_STOP, null);
            if (frame != null) sendFrame(frame);
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "stopScan error", e);
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

    // ==================== خيط الاستقبال ====================

    private void ensureRxExecutor() {
        if (rxExecutor == null || rxExecutor.isShutdown() || rxExecutor.isTerminated()) {
            rxExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "VanchReader-Rx");
                t.setDaemon(true);
                return t;
            });
        }
        rxExecutor.execute(this::receiveLoop);
    }

    /**
     * ✅ حلقة استقبال البيانات من القارئ
     */
    private void receiveLoop() {
        MiraFileLogger.log(TAG, "🔄 Receiver loop started");

        byte[] buffer = new byte[4096];

        while (!isReleased.get() && isConnected.get()) {
            try {
                if (inputStream == null) {
                    Thread.sleep(200);
                    continue;
                }

                int bytesRead = inputStream.read(buffer);
                if (bytesRead < 0) {
                    MiraFileLogger.logWarn(TAG, "⚠️ Connection closed by peer");
                    handleDisconnection();
                    break;
                }

                if (bytesRead > 0) {
                    processIncomingData(buffer, bytesRead);
                }

            } catch (SocketTimeoutException e) {
                // ✅ طبيعي — نستمر بالمراقبة
                continue;
            } catch (IOException e) {
                if (!isReleased.get()) {
                    MiraFileLogger.logError(TAG, "RX IOException: " + e.getMessage());
                    handleDisconnection();
                }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "RX unexpected error", e);
            }
        }

        MiraFileLogger.log(TAG, "🔄 Receiver loop ended");
    }

    /**
     * ✅ معالجة البيانات المستقبلة (قد تحتوي على عدة إطارات)
     */
    private void processIncomingData(byte[] buffer, int totalLength) {
        try {
            int offset = 0;

            while (offset < totalLength) {
                // ✅ ابحث عن بداية إطار صحيح
                if (buffer[offset] != VanchProtocol.FRAME_HEADER) {
                    offset++;
                    continue;
                }

                // ✅ نحتاج 5 بايتات على الأقل لقراءة الطول
                if (offset + 5 > totalLength) break;

                int dataLen = ((buffer[offset + 3] & 0xFF) << 8)
                            | (buffer[offset + 4] & 0xFF);
                int totalFrameLen = 1 + 1 + 1 + 2 + dataLen + 1 + 2;

                if (offset + totalFrameLen > totalLength) break;

                // ✅ استخرج الإطار
                byte[] frame = new byte[totalFrameLen];
                System.arraycopy(buffer, offset, frame, 0, totalFrameLen);

                // ✅ حلّل الإطار
                handleFrame(frame, totalFrameLen);

                offset += totalFrameLen;
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "processIncomingData error", e);
        }
    }

    /**
     * ✅ معالجة إطار واحد
     */
    private void handleFrame(byte[] frame, int length) {
        if (!VanchProtocol.isValidFrame(frame, length)) {
            MiraFileLogger.logWarn(TAG, "⚠️ Invalid frame (checksum mismatch)");
            return;
        }

        byte cmd = VanchProtocol.extractCommand(frame);

        // ✅ إذا كان إطار بيانات وسم
        if (cmd == VanchProtocol.RESP_INVENTORY_DATA
                || cmd == VanchProtocol.CMD_INVENTORY) {

            VanchProtocol.VanchTagData tagData = VanchProtocol.parseInventoryTag(frame, length);
            if (tagData != null && tagData.epc != null && !tagData.epc.isEmpty()) {

                if (tagData.rssi < rssiThreshold) {
                    // ✅ تجاهل الوسوم الضعيفة
                    return;
                }

                totalTagsRead++;
                lastTagTime = System.currentTimeMillis();

                MiraFileLogger.log(TAG, "📡 Tag read #" + totalTagsRead
                        + ": EPC=" + tagData.epc + " RSSI=" + tagData.rssi);

                if (listener != null) {
                    try {
                        listener.onTagRead(tagData.epc, tagData.rssi);
                    } catch (Exception e) {
                        MiraFileLogger.logError(TAG, "onTagRead callback error", e);
                    }
                }
            }
            return;
        }

        // ✅ استجابات الأوامر
        MiraFileLogger.log(TAG, "📥 Response: " + VanchProtocol.describeCommand(cmd)
                + " (len=" + length + ")");
    }

    /**
     * ✅ معالجة فقدان الاتصال
     */
    private void handleDisconnection() {
        if (!isConnected.compareAndSet(true, false)) return;

        isScanning.set(false);
        cleanupSocket();

        if (listener != null) {
            listener.onConnectionChanged(false);
        }

        // ✅ محاولة إعادة الاتصال
        if (shouldReconnect && !isReleased.get()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectThread != null && reconnectThread.isAlive()) return;

        reconnectThread = new Thread(() -> {
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                    if (isReleased.get() || !shouldReconnect) return;

                    MiraFileLogger.log(TAG, "🔄 Reconnect attempt " + attempt
                            + "/" + MAX_RECONNECT_ATTEMPTS);

                    if (connect()) {
                        MiraFileLogger.log(TAG, "✅ Reconnected successfully");
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            MiraFileLogger.logError(TAG, "❌ Max reconnect attempts reached", null);
        }, "Vanch-Reconnect");

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    // ==================== الإرسال ====================

    private synchronized boolean sendFrame(byte[] frame) {
        if (outputStream == null || !isConnected.get()) {
            MiraFileLogger.logWarn(TAG, "Cannot send — not connected");
            return false;
        }

        try {
            outputStream.write(frame);
            outputStream.flush();
            return true;
        } catch (IOException e) {
            MiraFileLogger.logError(TAG, "sendFrame error: " + e.getMessage());
            handleDisconnection();
            return false;
        }
    }

    private void sendRegionCommand() {
        byte[] data = new byte[]{ regionCode };
        byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_SET_REGION, data);
        if (frame != null) sendFrame(frame);
        MiraFileLogger.log(TAG, "🌍 Region sent: " + regionCode);
    }

    private void sendPowerCommand() {
        byte[] data = new byte[]{ (byte) powerDbm };
        byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_SET_POWER, data);
        if (frame != null) sendFrame(frame);
        MiraFileLogger.log(TAG, "⚡ Power sent: " + powerDbm + " dBm");
    }

    private void sendQueryParamCommand() {
        byte[] data = new byte[]{
            (byte) session,
            (byte) target,
            (byte) qValue
        };
        byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_SET_QUERY_PARAM, data);
        if (frame != null) sendFrame(frame);
        MiraFileLogger.log(TAG, "⚙️ Query params sent: S=" + session
                + " T=" + target + " Q=" + qValue);
    }

    // ==================== الإعدادات ====================

    @Override
    public void setPower(int powerDbm) {
        if (powerDbm < 5 || powerDbm > 30) {
            MiraFileLogger.logWarn(TAG, "Power out of range: " + powerDbm);
            return;
        }
        this.powerDbm = powerDbm;
        saveSettings();
        if (isConnected()) sendPowerCommand();
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

    public void setRegion(byte regionCode) {
        this.regionCode = regionCode;
        saveSettings();
        if (isConnected()) sendRegionCommand();
    }

    public void setSession(int session) {
        this.session = session;
        saveSettings();
        if (isConnected()) sendQueryParamCommand();
    }

    public void setTarget(int target) {
        this.target = target;
        saveSettings();
        if (isConnected()) sendQueryParamCommand();
    }

    public void setQValue(int qValue) {
        this.qValue = qValue;
        saveSettings();
        if (isConnected()) sendQueryParamCommand();
    }

    @Override
    public String getDeviceName() {
        return "VANCH Fixed Reader (" + host + ":" + port + ")";
    }

    @Override
    public String getFirmwareVersion() {
        // يمكن تطويرها لاحقاً بإرسال CMD_GET_INFO واستقبال الرد
        return "Unknown (VANCH)";
    }

    @Override
    public int getBatteryLevel() {
        return -1;  // متصل بالكهرباء
    }

    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }

    // ==================== عمليات الذاكرة ====================

    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (!isConnected() || data == null || data.isEmpty()) {
            return false;
        }

        try {
            byte[] dataBytes = VanchProtocol.hexToBytes(data);
            byte[] frame = VanchProtocol.buildMemoryCommand(
                VanchProtocol.CMD_WRITE_DATA,
                lastEpForMemoryOp, bank, ptr, dataBytes.length, pwdStr, dataBytes);

            if (frame == null) return false;
            return sendFrame(frame);
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "writeData error", e);
            return false;
        }
    }

    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        // ⚠️ VANCH يحتاج EPC قبل قراءة الذاكرة
        // هذا يتطلب تعقيداً إضافياً — سيُكمل بعد الحصول على البروتوكول الفعلي
        MiraFileLogger.logWarn(TAG, "readData: not yet implemented for VANCH");
        return null;
    }

    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        MiraFileLogger.logWarn(TAG, "setTagLockState: not yet implemented for VANCH");
        return false;
    }

    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        try {
            byte[] data = new byte[1 + 2 + filterData.length() / 2];
            data[0] = (byte) bank;
            data[1] = (byte) ((ptr >> 8) & 0xFF);
            data[2] = (byte) (ptr & 0xFF);
            byte[] filterBytes = VanchProtocol.hexToBytes(filterData);
            System.arraycopy(filterBytes, 0, data, 3, filterBytes.length);

            byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_SET_FILTER, data);
            return frame != null && sendFrame(frame);
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "setFilter error", e);
            return false;
        }
    }

    @Override
    public void clearFilter() {
        byte[] frame = VanchProtocol.buildCommand(VanchProtocol.CMD_CLEAR_FILTER, null);
        if (frame != null) sendFrame(frame);
    }

    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (newEpc == null || newEpc.isEmpty()) return false;
        return writeData(accessPassword, 1, 2, newEpc);
    }

    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return writeData(accessPassword, 3, address, data);
    }

    @Override
    public String readTid() {
        // ⚠️ يتطلب قراءة Bank 2 من وسم معروف — لاحقاً
        MiraFileLogger.logWarn(TAG, "readTid: not yet implemented for VANCH");
        return null;
    }

    @Override
    public String readUserMemory(int address, int length) {
        return readData(null, 3, address, length);
    }

    @Override
    public boolean lockTag(String accessPassword) {
        return setTagLockState(accessPassword, 1, true);
    }

    @Override
    public boolean unlockTag(String accessPassword) {
        return setTagLockState(accessPassword, 1, false);
    }

    @Override
    public boolean killTag(String accessPassword) {
        MiraFileLogger.logWarn(TAG, "killTag: not yet implemented for VANCH");
        return false;
    }

    // ==================== أدوات مساعدة ====================

    private String lastEpForMemoryOp = "";  // سيُحدّث عند قراءة EPC

    private void cleanupSocket() {
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } catch (IOException ignored) {}

        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException ignored) {}

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException ignored) {}
    }

    // ==================== Getters ====================

    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public byte getRegionCode() { return regionCode; }
    public int getSession() { return session; }
    public int getTarget() { return target; }
    public int getQValue() { return qValue; }
    public long getTotalTagsRead() { return totalTagsRead; }
}
