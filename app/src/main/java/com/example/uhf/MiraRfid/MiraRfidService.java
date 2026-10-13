package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.RfidPasswordManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ✅ MiraRfidService v5.4 — Thin Wrapper over RFIDManager
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  v5.4 (2026-10-13):
 *  ─────────────────────────────────────────────────────────────────────────
 *  ✅ readTid() مُحسَّن للمسح المتواصل (Inventory / POS / Gate)
 *  ✅ fallback إضافي: محاولة أخيرة بعد pause قصير
 *  ✅ لا تغيير في الواجهة العامة
 *
 *  v5.3:
 *  ─────────────────────────────────────────────────────────────────────────
 *  ✅ readEpc() لا تعتمد على pwdManager — تستدعي readData مباشرة
 *  ✅ readEpc() تُجرب 3 كلمات سر افتراضية (00000000, FFFFFFFF, فارغة)
 *  ✅ readData() لا تُفشل عند غياب كلمة السر — تستخدم قيمة افتراضية
 *  ✅ إصلاح جذري لمشكلة "readEpc returns NULL دائماً"
 *
 *  v5.2:
 *  ─────────────────────────────────────────────────────────────────────────
 *  ✅ readTid() صارم — 24 حرف فقط، مع 5 محاولات + fallback
 *  ✅ منع إرسال TID مقطوع (20 حرف)
 *
 *  v5.0:
 *  ─────────────────────────────────────────────────────────────────────────
 *  ✅ كل العمليات تُفوَّض إلى RFIDManager.getActiveReader()
 *
 *  المعمارية:
 *  ─────────────────────────────────────────────────────────────────────────
 *     BaseTabFragmentActivity  → initUHF (تهيئة SDK مرة واحدة)
 *     RFIDManager              → إدارة القارئ النشط (Singleton)
 *     ChainwayReader / Simulator → التطبيق الفعلي
 *     MiraRfidService          → واجهة مبسّطة + عمليات API السحابية
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class MiraRfidService {

    private static final String TAG = "MiraRfidService";

    // ==================== ثوابت ====================
    private static final int EPC_BANK = 1;
    private static final int EPC_START_WORD = 2;
    private static final int EPC_READ_LENGTH_WORDS = 6;

    private static final int TID_BANK = 2;
    private static final int TID_START_WORD = 0;

    /** ✅ [v5.2] قراءة 6 words = 24 حرف بالضبط — لا TID مقطوع */
    private static final int TID_READ_LENGTH_WORDS = 6;
    /** ✅ [v5.2] عدد محاولات قراءة TID قبل الفشل */
    private static final int TID_READ_MAX_ATTEMPTS = 5;
    /** ✅ [v5.2] فاصل بين محاولات القراءة (ms) */
    private static final long TID_RETRY_DELAY_MS = 80;
    /** ✅ [v5.4] فاصل قبل المحاولة النهائية بعد فشل كل شيء (ms) */
    private static final long TID_FINAL_RETRY_PAUSE_MS = 120;

    /** ✅ [v5.3] كلمات سر افتراضية للقراءة/الكتابة */
    private static final String[] DEFAULT_PASSWORDS = {
            "00000000", "FFFFFFFF", ""
    };

    /** ✅ [v5.3] كلمة سر افتراضية للاستخدام الفوري */
    private static final String FALLBACK_PASSWORD = "00000000";

    /** مهلة انتظار جهوزية RFIDManager */
    private static final long READER_DETECT_TIMEOUT_SEC = 8;

    // ==================== Singleton ====================
    private static volatile MiraRfidService instance;

    private final Context appContext;
    private final RfidPasswordManager pwdManager;

    private final AtomicReference<OperationState> currentState =
            new AtomicReference<>(OperationState.IDLE);
    private final AtomicReference<String> lastReadEpc = new AtomicReference<>("");
    private final AtomicReference<String> lastReadTid = new AtomicReference<>("");
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /** Latch يُفتح عند جهوزية RFIDManager */
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicBoolean isReaderReady = new AtomicBoolean(false);
    private volatile Thread waitThread;

    // ==================== Enums ====================

    public enum OperationState {
        IDLE, READING, WRITING, VERIFYING, REGISTERING, COMPLETED, FAILED
    }

    // ==================== Interfaces ====================

    public interface ReaderReadyCallback {
        void onReady(boolean success, String deviceName, boolean isSimulation);
    }

    // ==================== Constructor ====================

    private MiraRfidService(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("MiraRfidService: context cannot be null");
        }
        this.appContext = context.getApplicationContext();

        RfidPasswordManager tempPwd = null;
        try {
            tempPwd = RfidPasswordManager.getInstance(this.appContext);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init RfidPasswordManager", t);
        }
        this.pwdManager = tempPwd;

        startWaitingForReaderReady();
    }

    public static MiraRfidService getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (MiraRfidService.class) {
                if (instance == null) {
                    instance = new MiraRfidService(context);
                }
            }
        }
        return instance;
    }

    public static void getInstanceAsync(@NonNull Context context,
                                         @Nullable ReaderReadyCallback callback) {
        MiraRfidService svc = getInstance(context);
        if (callback == null) return;

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            callback.onReady(
                    svc.isReaderReady.get(),
                    svc.getDeviceName(),
                    svc.isSimulationMode()
            );
        });
    }

    /**
     * ✅ خيط انتظار خفيف — لا يُهيّئ القارئ، فقط يراقب الحالة
     */
    private void startWaitingForReaderReady() {
        waitThread = new Thread(() -> {
            final long timeout = READER_DETECT_TIMEOUT_SEC * 1000;
            final long start = System.currentTimeMillis();

            while (!isShutdown.get()
                    && (System.currentTimeMillis() - start) < timeout) {

                if (BaseTabFragmentActivity.isRfidDevicePresent()) {
                    isReaderReady.set(true);
                    readyLatch.countDown();
                    Log.i(TAG, "✅ RFIDManager ready — MiraRfidService operational");
                    return;
                }

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.w(TAG, "⚠️ Reader not detected within timeout — operations will retry lazily");
            readyLatch.countDown();
        }, "MiraRFID-WaitForReader");

        waitThread.setDaemon(true);
        waitThread.setPriority(Thread.NORM_PRIORITY - 1);
        waitThread.start();
    }

    // ==================== Reader Access ====================

    @Nullable
    private MiraIRFIDReader getReader() {
        try {
            return RFIDManager.getInstance(appContext).getActiveReader();
        } catch (Throwable t) {
            Log.e(TAG, "getReader error", t);
            return null;
        }
    }

    @Nullable
    private MiraIRFIDReader getConnectedReader() {
        MiraIRFIDReader reader = getReader();
        if (reader == null) return null;

        if (!reader.isConnected()) {
            try {
                if (!reader.initialize()) {
                    Log.w(TAG, "getConnectedReader: initialize() failed");
                    return null;
                }
                if (!reader.connect()) {
                    Log.w(TAG, "getConnectedReader: connect() failed");
                    return null;
                }
            } catch (Throwable t) {
                Log.e(TAG, "getConnectedReader exception", t);
                return null;
            }
        }
        return reader;
    }

    // ==================== Public API ====================

    public boolean waitForReader(long timeoutMs) {
        if (isReaderReady.get()) return getReader() != null;
        try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return getReader() != null;
    }

    public boolean isReady() {
        return isReaderReady.get() && getReader() != null && !isShutdown.get();
    }

    public synchronized void release() {
        if (isShutdown.compareAndSet(false, true)) {
            Log.i(TAG, "Shutting down MiraRfidService");
        }

        Thread t = waitThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        readyLatch.countDown();
        instance = null;
    }

    public boolean isSimulationMode() {
        MiraIRFIDReader reader = getReader();
        return reader instanceof SimulatorReader;
    }

    public MiraIRFIDReader getActiveReader() { return getReader(); }
    public OperationState getCurrentState() { return currentState.get(); }
    public String getLastReadEpc() { return lastReadEpc.get(); }
    public String getLastReadTid() { return lastReadTid.get(); }
    public boolean isShutdown() { return isShutdown.get(); }

    // ==================== Password ====================

    /**
     * ✅ [v5.3] إرجاع كلمة سر صالحة — لا تُعيد null أبداً
     */
    @NonNull
    private String resolvePassword(@Nullable String pwdStr, boolean forWrite) {
        // 1) الأولوية: المُرسل صراحةً
        if (pwdStr != null && !pwdStr.trim().isEmpty()) {
            return pwdStr.trim().toUpperCase(Locale.US);
        }

        // 2) من pwdManager
        if (pwdManager != null) {
            try {
                String managed = pwdManager.getPasswordForTag(lastReadTid.get());
                if (managed != null && !managed.isEmpty()) {
                    Log.d(TAG, "🔑 Password from RfidPasswordManager (mode="
                            + pwdManager.getPasswordMode() + ")");
                    return managed;
                }
            } catch (Throwable t) {
                Log.e(TAG, "resolvePassword error", t);
            }
        }

        // 3) fallback
        if (forWrite) {
            Log.w(TAG, "⚠️ No password from manager — using fallback '"
                    + FALLBACK_PASSWORD + "' for write operation");
        } else {
            Log.d(TAG, "ℹ️ No password available — using fallback for read");
        }
        return FALLBACK_PASSWORD;
    }

    // ==================== Low-level Operations ====================

    public boolean writeData(@Nullable String pwdStr, int bank, int ptr,
                              @Nullable String data) {
        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) {
            Log.w(TAG, "writeData: no reader");
            return false;
        }
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "writeData: empty data");
            return false;
        }

        String safePwd = resolvePassword(pwdStr, true);

        Log.d(TAG, "📝 writeData: bank=" + bank + " ptr=" + ptr
                + " len=" + data.length() + " pwd=****");

        try {
            return reader.writeData(safePwd, bank, ptr, data);
        } catch (Throwable t) {
            Log.e(TAG, "writeData exception", t);
            return false;
        }
    }

    /**
     * ✅ [v5.3] readData — لا تُفشل عند غياب كلمة السر
     */
    @Nullable
    public String readData(@Nullable String pwdStr, int bank, int ptr, int len) {
        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) {
            Log.w(TAG, "readData: no reader");
            return null;
        }

        String safePwd = resolvePassword(pwdStr, false);

        Log.d(TAG, "📖 readData: bank=" + bank + " ptr=" + ptr + " len=" + len);

        try {
            return reader.readData(safePwd, bank, ptr, len);
        } catch (Throwable t) {
            Log.e(TAG, "readData exception", t);
            return null;
        }
    }

    public boolean setTagLockState(@Nullable String pwdStr, int bank,
                                    boolean shouldLock) {
        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) return false;

        String safePwd = resolvePassword(pwdStr, true);

        Log.i(TAG, "🔐 setTagLockState: bank=" + bank
                + " lock=" + shouldLock + " pwd=****");

        try {
            return reader.setTagLockState(safePwd, bank, shouldLock);
        } catch (Throwable t) {
            Log.e(TAG, "setTagLockState exception", t);
            return false;
        }
    }

    public boolean setFilter(int bank, int ptr, @Nullable String filterData) {
        MiraIRFIDReader reader = getReader();
        if (reader == null) {
            if (isSimulationMode()) {
                Log.d(TAG, "setFilter: no-op in simulation");
                return true;
            }
            return false;
        }
        try {
            return reader.setFilter(bank, ptr, filterData);
        } catch (Throwable t) {
            Log.e(TAG, "setFilter exception", t);
            return false;
        }
    }

    public void clearFilter() {
        MiraIRFIDReader reader = getReader();
        if (reader != null) {
            try {
                reader.clearFilter();
            } catch (Throwable t) {
                Log.e(TAG, "clearFilter exception", t);
            }
        }
    }

    public void resetForNewTag() {
        MiraIRFIDReader reader = getReader();
        if (reader instanceof SimulatorReader) {
            try {
                ((SimulatorReader) reader).resetForNewTag();
                Log.d(TAG, "✅ Simulator reset for new tag");
            } catch (Throwable t) {
                Log.e(TAG, "resetForNewTag exception", t);
            }
        }
    }

    // ==================== High-level Operations ====================

    /**
     * ═══════════════════════════════════════════════════════════════════════
     *  ✅ [v5.3] readEpc — لا تعتمد على pwdManager
     * ═══════════════════════════════════════════════════════════════════════
     *
     *  - تستخدم readData مباشرة مع 3 كلمات سر افتراضية
     *  - لا تستدعي reader.readEpc() (غير موجودة في MiraIRFIDReader)
     *  - لا تعتمد على resolvePassword (تستخدم كلمات سر ثابتة)
     */
    @Nullable
    public String readEpc(@Nullable String accessPassword) {
        currentState.set(OperationState.READING);

        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) {
            currentState.set(OperationState.FAILED);
            Log.e(TAG, "❌ readEpc: no reader");
            return null;
        }

        // ═══════════════════════════════════════════════════════
        //  ✅ تجربة 3 كلمات سر — لا شيء غير readData
        // ═══════════════════════════════════════════════════════
        for (String pwd : DEFAULT_PASSWORDS) {
            try {
                Log.d(TAG, "🔄 readEpc: trying readData (pwd='"
                        + (pwd.isEmpty() ? "empty" : "****") + "')...");
                String epc = reader.readData(pwd, EPC_BANK,
                        EPC_START_WORD, EPC_READ_LENGTH_WORDS);
                if (epc != null && !epc.isEmpty()) {
                    String normalized = normalizeEpc(epc);
                    if (normalized.length() >= 8) {
                        lastReadEpc.set(normalized);
                        currentState.set(OperationState.COMPLETED);
                        Log.i(TAG, "✅ readEpc OK (readData pwd='"
                                + (pwd.isEmpty() ? "empty" : "****") + "'): "
                                + normalized + " (len=" + normalized.length() + ")");
                        return normalized;
                    } else {
                        Log.w(TAG, "⚠️ readEpc readData returned short: '"
                                + normalized + "' (len=" + normalized.length() + ")");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "readEpc readData failed (pwd='"
                        + (pwd.isEmpty() ? "empty" : "****") + "'): "
                        + t.getMessage());
            }
        }

        currentState.set(OperationState.FAILED);
        Log.e(TAG, "❌ readEpc FAILED after 3 attempts (readData only)");
        return null;
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════
     *  ✅ [v5.4] قراءة TID صارمة — مُحسَّنة للمسح المتواصل
     * ═══════════════════════════════════════════════════════════════════════
     *
     *  التسلسل:
     *   1) 5 محاولات عبر reader.readTid() (مع 80ms فاصل)
     *   2) Fallback عبر readData (bank 2, 3 كلمات سر)
     *   3) ✅ [v5.4 جديد] Pause قصير + محاولة أخيرة
     *
     *  — مُستخدمة من: التكويد / POS / الجرد
     *  — التوقيت الكلي الأسوأ: ~700ms
     */
    @Nullable
    public String readTid(@Nullable String accessPassword) {
        currentState.set(OperationState.READING);

        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) {
            currentState.set(OperationState.FAILED);
            Log.e(TAG, "❌ readTid: no reader");
            return null;
        }

        // ═══════════════════════════════════════════════════════
        //  ① 5 محاولات عبر reader.readTid()
        // ═══════════════════════════════════════════════════════
        for (int attempt = 1; attempt <= TID_READ_MAX_ATTEMPTS; attempt++) {
            try {
                String rawTid = reader.readTid();
                if (rawTid != null && !rawTid.isEmpty()) {
                    String cleaned = normalizeTid(rawTid);
                    if (cleaned.length() == 24
                            && cleaned.matches("^[0-9A-F]{24}$")) {
                        lastReadTid.set(cleaned);
                        currentState.set(OperationState.COMPLETED);
                        Log.i(TAG, "✅ readTid OK (attempt " + attempt + "): "
                                + cleaned);
                        return cleaned;
                    } else {
                        Log.w(TAG, "⚠️ readTid attempt " + attempt
                                + " wrong length=" + cleaned.length()
                                + " value=" + cleaned);
                    }
                } else {
                    Log.w(TAG, "⚠️ readTid attempt " + attempt
                            + " returned null/empty");
                }
            } catch (Throwable t) {
                Log.w(TAG, "readTid attempt " + attempt
                        + " error: " + t.getMessage());
            }

            if (attempt < TID_READ_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(TID_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        //  ② Fallback عبر readData (bank 2)
        // ═══════════════════════════════════════════════════════
        for (String pwd : DEFAULT_PASSWORDS) {
            try {
                Log.d(TAG, "🔄 readTid fallback via readData (pwd='"
                        + (pwd.isEmpty() ? "empty" : "****") + "')...");
                String rawTid = reader.readData(pwd,
                        TID_BANK, TID_START_WORD, TID_READ_LENGTH_WORDS);
                if (rawTid != null && !rawTid.isEmpty()) {
                    String cleaned = normalizeTid(rawTid);
                    if (cleaned.length() == 24
                            && cleaned.matches("^[0-9A-F]{24}$")) {
                        lastReadTid.set(cleaned);
                        currentState.set(OperationState.COMPLETED);
                        Log.i(TAG, "✅ readTid fallback OK: " + cleaned);
                        return cleaned;
                    } else {
                        Log.w(TAG, "⚠️ readTid fallback wrong length="
                                + cleaned.length() + " value=" + cleaned);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "readTid fallback (pwd='"
                        + (pwd.isEmpty() ? "empty" : "****") + "') failed: "
                        + t.getMessage());
            }
        }

        // ═══════════════════════════════════════════════════════
        //  ③ ✅ [v5.4] محاولة أخيرة بعد pause قصير
        //     — تُفيد في المسح المتواصل حيث القارئ مشغول
        // ═══════════════════════════════════════════════════════
        try {
            Log.d(TAG, "🔄 readTid: final attempt after "
                    + TID_FINAL_RETRY_PAUSE_MS + "ms pause...");
            Thread.sleep(TID_FINAL_RETRY_PAUSE_MS);

            String rawTid = reader.readTid();
            if (rawTid != null && !rawTid.isEmpty()) {
                String cleaned = normalizeTid(rawTid);
                if (cleaned.length() == 24
                        && cleaned.matches("^[0-9A-F]{24}$")) {
                    lastReadTid.set(cleaned);
                    currentState.set(OperationState.COMPLETED);
                    Log.i(TAG, "✅ readTid OK (final attempt): " + cleaned);
                    return cleaned;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.w(TAG, "readTid final attempt failed: " + t.getMessage());
        }

        // ═══════════════════════════════════════════════════════
        //  فشل كامل
        // ═══════════════════════════════════════════════════════
        currentState.set(OperationState.FAILED);
        Log.e(TAG, "❌ readTid FAILED after "
                + TID_READ_MAX_ATTEMPTS + " attempts + readData fallback "
                + "+ final pause attempt — no valid TID (24 chars) obtained");
        return null;
    }

    @NonNull
    private String normalizeTid(@NonNull String raw) {
        String tid = raw.trim().toUpperCase(Locale.US);
        return tid;
    }

    public boolean writeEpc(@Nullable String newEpc, @Nullable String accessPassword) {
        currentState.set(OperationState.WRITING);

        if (newEpc == null || newEpc.isEmpty()) {
            Log.w(TAG, "writeEpc: empty EPC");
            currentState.set(OperationState.FAILED);
            return false;
        }

        String normalizedEpc = newEpc.trim().toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        if (normalizedEpc.length() != 24) {
            Log.e(TAG, "writeEpc: INVALID LENGTH=" + normalizedEpc.length()
                    + " (must be exactly 24), value='" + normalizedEpc + "'");
            currentState.set(OperationState.FAILED);
            return false;
        }

        if (normalizedEpc.startsWith("3008")) {
            Log.e(TAG, "writeEpc: REFUSED — EPC starts with TID prefix '3008'");
            currentState.set(OperationState.FAILED);
            return false;
        }

        if (!normalizedEpc.startsWith("E2")) {
            Log.w(TAG, "writeEpc: EPC doesn't start with E2 (warning only)");
        }

        MiraIRFIDReader reader = getConnectedReader();
        if (reader == null) {
            Log.w(TAG, "writeEpc: no reader");
            currentState.set(OperationState.FAILED);
            return false;
        }

        Log.i(TAG, "📝 writeEpc: epc=" + normalizedEpc + " pwd=****");

        boolean success = writeData(accessPassword, EPC_BANK,
                EPC_START_WORD, normalizedEpc);

        if (success) {
            lastReadEpc.set(normalizedEpc);
            currentState.set(OperationState.COMPLETED);
            Log.i(TAG, "✅ writeEpc OK");
        } else {
            currentState.set(OperationState.FAILED);
            Log.e(TAG, "❌ writeEpc FAILED");
        }
        return success;
    }

    public boolean verifyWrite(@Nullable String expectedEpc,
                                @Nullable String accessPassword) {
        currentState.set(OperationState.VERIFYING);
        try {
            if (expectedEpc == null || expectedEpc.isEmpty()) return false;

            String readBack = readEpc(accessPassword);
            if (readBack == null) {
                Log.e(TAG, "❌ verifyWrite: readBack is null");
                return false;
            }

            String expectedNorm = normalizeEpc(expectedEpc);
            String actualNorm = normalizeEpc(readBack);

            if (expectedNorm.equalsIgnoreCase(actualNorm)) {
                Log.i(TAG, "✅ verifyWrite OK: " + actualNorm);
                currentState.set(OperationState.COMPLETED);
                return true;
            }

            Log.e(TAG, "❌ verifyWrite FAILED: expected=" + expectedNorm
                    + " read=" + actualNorm);
            currentState.set(OperationState.FAILED);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "verifyWrite exception", t);
            currentState.set(OperationState.FAILED);
            return false;
        }
    }

    /**
     * ✅ [v5.1] توليد EPC صحيح 24 حرف Hex (96 bit) من mira_id
     */
    @NonNull
    public String generateEpcFromMiraId(@Nullable String miraId) {

        if (miraId == null || miraId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "generateEpcFromMiraId: miraId cannot be null or empty");
        }

        String numericPart = miraId.replaceAll("[^0-9]", "");
        if (numericPart.isEmpty()) {
            numericPart = String.valueOf(
                    System.currentTimeMillis() % 1_000_000_000L);
        }

        long serialValue;
        try {
            serialValue = Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            serialValue = Math.abs(miraId.hashCode()) & 0xFFFFFFFFFFL;
        }

        final String header = "E2";
        final String filterPartition = "34";
        final String companyHex = "0A1B2";

        long itemCode = Math.abs(miraId.hashCode()) & 0xFFFFL;
        String itemHex = String.format(Locale.US, "%04X", itemCode);

        long serialLong = serialValue & 0xFFFFFFFFFFFL;
        String serialHex = String.format(Locale.US, "%011X", serialLong);

        String epc = header
                + filterPartition
                + companyHex
                + itemHex
                + serialHex;

        epc = epc.toUpperCase(Locale.US);

        if (epc.length() != 24) {
            throw new IllegalStateException(
                    "generateEpcFromMiraId: generated EPC has wrong length="
                            + epc.length() + " value='" + epc + "'");
        }
        if (!epc.matches("^[0-9A-F]{24}$")) {
            throw new IllegalStateException(
                    "generateEpcFromMiraId: generated EPC contains invalid chars='"
                            + epc + "'");
        }
        if (epc.startsWith("3008")) {
            throw new IllegalStateException(
                    "generateEpcFromMiraId: FATAL — generated EPC starts with TID prefix '3008'");
        }

        Log.i(TAG, "✅ Generated valid EPC: " + epc
                + " (from mira_id='" + miraId + "')");
        return epc;
    }

    @NonNull
    public String getDeviceName() {
        MiraIRFIDReader reader = getReader();
        if (reader == null) return "جاري التحضير...";
        try {
            String name = reader.getDeviceName();
            if (isSimulationMode()) name += " (محاكي)";
            return name;
        } catch (Throwable t) {
            return "غير معروف";
        }
    }

    @NonNull
    public String getStateDescription() {
        switch (currentState.get()) {
            case IDLE: return "جاهز";
            case READING: return "جاري القراءة...";
            case WRITING: return "جاري الكتابة...";
            case VERIFYING: return "جاري التحقق...";
            case REGISTERING: return "جاري التسجيل...";
            case COMPLETED: return "تمت العملية بنجاح";
            case FAILED: return "فشلت العملية";
            default: return "";
        }
    }

    public String getSecurityInfo() {
        if (pwdManager == null) return "RfidPasswordManager: not available";
        try {
            return pwdManager.getDiagnosticInfo();
        } catch (Throwable t) {
            return "Error: " + t.getMessage();
        }
    }

    // ==================== API Operations ====================

    public boolean registerRfidBinding(@Nullable String miraId,
                                        @Nullable String epc,
                                        @Nullable String tid) {
        currentState.set(OperationState.REGISTERING);
        if (miraId == null || epc == null || tid == null) {
            Log.w(TAG, "registerRfidBinding: null parameters");
            currentState.set(OperationState.FAILED);
            return false;
        }

        try {
            Log.d(TAG, "📡 تسجيل الربط في MIRA: " + miraId + " → " + epc);
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);

            JSONObject payload = new JSONObject();
            payload.put("mira_id", miraId);
            payload.put("epc", epc);
            payload.put("tid", tid);
            payload.put("gate_id", "handheld_c72");
            payload.put("device_type",
                    isSimulationMode() ? "simulator" : "chainway_c72");
            payload.put("operation_type", "register");

            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/register_rfid", payload);

            if (response != null && response.isSuccess) {
                currentState.set(OperationState.COMPLETED);
                Log.d(TAG, "✅ تم التسجيل بنجاح");
                return true;
            }

            currentState.set(OperationState.FAILED);
            return false;

        } catch (Throwable t) {
            Log.e(TAG, "registerRfidBinding error", t);
            currentState.set(OperationState.FAILED);
            return false;
        }
    }

    @Nullable
    public JSONObject searchInMira(@Nullable String identifier,
                                    @Nullable String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            Log.w(TAG, "searchInMira: null identifier");
            return null;
        }

        try {
            Log.d(TAG, "🔍 البحث في MIRA: " + identifier);
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);

            JSONObject searchPayload = new JSONObject();
            searchPayload.put("identifier", identifier);
            searchPayload.put("identifier_type",
                    identifierType != null ? identifierType : "auto");
            searchPayload.put("gate_id", "handheld_c72");

            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/authorize", searchPayload);

            if (response != null && response.isSuccess && response.data != null) {
                JSONObject item = response.data.optJSONObject("item");
                if (item != null) {
                    JSONObject result = new JSONObject();
                    result.put("item", item);
                    result.put("barcode", safeOpt(response.data, "barcode"));
                    result.put("stock", safeOpt(response.data, "stock"));
                    result.put("location", safeOpt(response.data, "location"));
                    Log.d(TAG, "✅ تم العثور على القطعة");
                    return result;
                }
            }

            if (isSimulationMode()) {
                return createSimulatedSearchResult(identifier);
            }
            return null;

        } catch (Throwable t) {
            Log.e(TAG, "searchInMira error", t);
            if (isSimulationMode()) {
                return createSimulatedSearchResult(identifier);
            }
            return null;
        }
    }

    private JSONObject safeOpt(@NonNull JSONObject parent, @NonNull String key) {
        JSONObject child = parent.optJSONObject(key);
        return child != null ? child : new JSONObject();
    }

    @NonNull
    private JSONObject createSimulatedSearchResult(@NonNull String identifier) {
        try {
            JSONObject result = new JSONObject();
            JSONObject item = new JSONObject();
            item.put("title", "قطعة تجريبية - " + identifier);
            item.put("serial", identifier);
            item.put("karat", "21K");
            item.put("weight", "5.24");
            item.put("category", "Rings");
            item.put("status", "available");
            item.put("mira_id", identifier);

            JSONObject barcode = new JSONObject();
            barcode.put("gtin", generateGtinFromIdentifier(identifier));
            barcode.put("serial_no", identifier);

            JSONObject stock = new JSONObject();
            stock.put("status", "متوفر");
            stock.put("branch_name", "الفرع الرئيسي");

            JSONObject location = new JSONObject();
            location.put("zone_name", "منطقة الخواتم");
            location.put("shelf_code", "R-A1");

            result.put("item", item);
            result.put("barcode", barcode);
            result.put("stock", stock);
            result.put("location", location);
            return result;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    @NonNull
    private String generateGtinFromIdentifier(@NonNull String identifier) {
        String numbers = identifier.replaceAll("[^0-9]", "");
        if (numbers.isEmpty()) {
            numbers = String.valueOf(System.currentTimeMillis());
        }
        while (numbers.length() < 12) numbers = "0" + numbers;
        return numbers.substring(0, 12);
    }

    public boolean verifyTagAllowed(@Nullable String tid) {
        if (tid == null || tid.isEmpty()) {
            Log.w(TAG, "verifyTagAllowed: null tid");
            return isSimulationMode();
        }

        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            MiraApiClient.ApiResponse response = apiClient.getWithAuth(
                    "/rfid-inventory/verify?tid=" + tid);

            if (response != null && response.isSuccess && response.data != null) {
                return response.data.optBoolean("approved", false);
            }
            return isSimulationMode();
        } catch (Throwable t) {
            Log.e(TAG, "verifyTagAllowed error", t);
            return isSimulationMode();
        }
    }

    public boolean markTagProgrammed(@Nullable String tid,
                                      @Nullable String miraId,
                                      @Nullable String epc) {
        if (tid == null || miraId == null || epc == null) {
            Log.w(TAG, "markTagProgrammed: null parameters");
            return false;
        }

        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            JSONObject payload = new JSONObject();
            payload.put("tid", tid);
            payload.put("mira_id", miraId);
            payload.put("epc", epc);

            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/rfid-inventory/programmed", payload);

            if (response != null && response.isSuccess) {
                Log.d(TAG, "✅ markTagProgrammed OK");
                return true;
            }

            Log.w(TAG, "markTagProgrammed failed — saving locally");
            saveProgrammedOffline(tid, miraId, epc);
            return isSimulationMode();

        } catch (Throwable t) {
            Log.e(TAG, "markTagProgrammed error", t);
            saveProgrammedOffline(tid, miraId, epc);
            return isSimulationMode();
        }
    }

    private void saveProgrammedOffline(String tid, String miraId, String epc) {
        try {
            JSONObject record = new JSONObject();
            record.put("tid", tid);
            record.put("mira_id", miraId);
            record.put("epc", epc);
            record.put("saved_at", System.currentTimeMillis());

            SharedPreferences prefs = appContext.getSharedPreferences(
                    "rfid_programmed_queue", Context.MODE_PRIVATE);
            String existing = prefs.getString("queue", "[]");
            JSONArray arr = new JSONArray(existing);
            arr.put(record);
            while (arr.length() > 100) arr.remove(0);
            prefs.edit().putString("queue", arr.toString()).apply();
        } catch (Throwable t) {
            Log.e(TAG, "saveProgrammedOffline error", t);
        }
    }

    public boolean importBatch(@Nullable String batchNumber,
                                @Nullable JSONArray tags) {
        if (batchNumber == null || tags == null) return false;
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            JSONObject payload = new JSONObject();
            payload.put("batch_number", batchNumber);
            payload.put("tags", tags);
            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/rfid-inventory/import", payload);
            return response != null && response.isSuccess;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    public JSONObject getRfidInventoryStats() {
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            MiraApiClient.ApiResponse response =
                    apiClient.getWithAuth("/rfid-inventory/stats");

            if (response != null && response.isSuccess && response.data != null) {
                return response.data;
            }
            if (isSimulationMode()) {
                JSONObject stats = new JSONObject();
                stats.put("total", 100);
                stats.put("available", 80);
                stats.put("programmed", 15);
                stats.put("defective", 3);
                stats.put("lost", 2);
                stats.put("_simulated", true);
                return stats;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== Utilities ====================

    @NonNull
    private String normalizeEpc(@Nullable String epc) {
        if (epc == null) return "";
        return epc.trim().toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");
    }

    @NonNull
    public String getHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 MiraRfidService Health\n");
        sb.append("─────────────\n");
        sb.append("Shutdown: ").append(isShutdown.get()).append("\n");
        sb.append("Reader present: ")
                .append(BaseTabFragmentActivity.isRfidDevicePresent()).append("\n");
        sb.append("Reader ready: ").append(isReaderReady.get()).append("\n");
        sb.append("Simulation: ").append(isSimulationMode()).append("\n");
        sb.append("State: ").append(currentState.get()).append("\n");
        sb.append("Device: ").append(getDeviceName()).append("\n");
        sb.append("Pwd manager: ")
                .append(pwdManager != null ? "OK" : "NULL").append("\n");
        return sb.toString();
    }
}
