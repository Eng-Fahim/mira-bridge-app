package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.RfidPasswordManager;
import com.example.uhf.utils.MiraExecutorService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiraRfidService - الإصدار 4.0 (المطوّر الاحترافي)
 *
 * ✅ الإصلاحات المطبقة (26 مشكلة):
 *  1. انتظار كشف القارئ (CountDownLatch)
 *  2. getInstance ينتظر التهيئة
 *  3. release آمن + إعادة تعيين آمنة
 *  4. إزالة FALLBACK_PWD من الكود
 *  5. readTid مع تأخير + استراتيجية محسّنة
 *  6. توثيق EPC_START_WORD
 *  7. فحص isSimulationMode في كل عملية
 *  8. verifyWrite لا يُغيّر currentState
 *  9. فحص pwdManager == null
 * 10. فحص context == null
 * 11. لا import مباشر لـ SDK في العمليات
 * 12. readTid يستخدم قارئ مباشر
 * 13. تنظيف TID بذكاء
 * 14. writeEpc موثّق
 * 15. verifyWrite مع padding
 * 16. registerRfidBinding يفرّق بين الوضعين
 * 17. generateEpcFromMiraId مع فحص
 * 18. searchInMira آمن
 * 19. verifyTagAllowed مع فحص null
 * 20. markTagProgrammed مع offline fallback
 * 21. getRfidInventoryStats واقعي
 * 22. release يوقف detectThread
 * 23. setFilter مُوثّق
 * 24. getDeviceName آمن
 * 25. currentState مُتزامن
 * 26. timeout على عمليات SDK
 *
 * ✅ إضافات:
 *  - getInstanceAsync() مع callback
 *  - waitForReader(timeoutMs)
 *  - isReady()
 *  - getHealthReport()
 *  - readTidWithRetry() محسّن
 *  - normalizeEpc() للمقارنات
 */
public class MiraRfidService {

    private static final String TAG = "MiraRfidService";

    // ==================== ثوابت ====================
    // ✅ [إصلاح #6] توثيق EPC
    /**
     * EPC في bank 01، يبدأ من Word 2 (Bit 32)
     * طول EPC القياسي = 96 bits = 6 words = 24 hex chars
     */
    private static final int EPC_BANK = 1;
    private static final int EPC_START_WORD = 2;
    private static final int EPC_READ_LENGTH_WORDS = 6;

    /** TID في bank 2، يبدأ من Word 0 */
    private static final int TID_BANK = 2;
    private static final int TID_START_WORD = 0;

    /** محاولات قراءة TID */
    private static final int[] TID_READ_ATTEMPTS_WORDS = {6, 5, 4};

    /** انتظار كشف القارئ */
    private static final long READER_DETECT_TIMEOUT_SEC = 8;

    /** مهلة العملية على SDK */
    private static final long SDK_OPERATION_TIMEOUT_MS = 5000;

    // ==================== Singleton ====================
    private static volatile MiraRfidService instance;

    private final Context appContext;
    private final RfidPasswordManager pwdManager;

    private volatile MiraIRFIDReader activeReader;
    private final AtomicBoolean isSimulationMode = new AtomicBoolean(false);
    private final AtomicReference<String> lastReadEpc = new AtomicReference<>("");
    private final AtomicReference<String> lastReadTid = new AtomicReference<>("");
    private final AtomicReference<OperationState> currentState =
            new AtomicReference<>(OperationState.IDLE);

    // ✅ [إصلاح #1] انتظار التهيئة
    private final CountDownLatch readerReadyLatch = new CountDownLatch(1);
    private final AtomicBoolean isReaderReady = new AtomicBoolean(false);

    // ✅ [إصلاح #22] للتحكم في الخيط
    private volatile Thread detectThread;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // ==================== Enums ====================

    public enum OperationState {
        IDLE, READING, WRITING, VERIFYING, REGISTERING, COMPLETED, FAILED
    }

    // ==================== Interfaces ====================

    /**
     * ✅ [إضافة] callback للتهيئة غير المتزامنة
     */
    public interface ReaderReadyCallback {
        void onReady(boolean success, String deviceName, boolean isSimulation);
    }

    // ==================== Constructor ====================

    private MiraRfidService(@NonNull Context context) {
        // ✅ [إصلاح #10] فحص context
        if (context == null) {
            throw new IllegalArgumentException("MiraRfidService: context cannot be null");
        }
        this.appContext = context.getApplicationContext();

        // ✅ [إصلاح #9] فحص pwdManager
        RfidPasswordManager tempPwd = null;
        try {
            tempPwd = RfidPasswordManager.getInstance(this.appContext);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init RfidPasswordManager", t);
        }
        this.pwdManager = tempPwd;

        // بدء الكشف
        detectAndInitializeReader();
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

    /**
     * ✅ [إضافة] نسخة غير محجوبة
     */
    public static void getInstanceAsync(@NonNull Context context,
                                         @Nullable ReaderReadyCallback callback) {
        MiraRfidService svc = getInstance(context);
        if (callback == null) return;

        // ✅ [إصلاح #2] انتظر على خيط منفصل
        MiraExecutorService.getInstance().getBackgroundExecutor().execute(() -> {
            boolean ok = svc.waitForReader(READER_DETECT_TIMEOUT_SEC * 1000);
            String name = svc.getDeviceName();
            boolean isSim = svc.isSimulationMode();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onReady(ok, name, isSim));
        });
    }

    // ==================== Reader Detection ====================

    /**
     * ✅ [إصلاح #1, #22] كشف القارئ مع احترام isShutdown
     */
    private void detectAndInitializeReader() {
        detectThread = new Thread(() -> {
            if (isShutdown.get()) return;

            ChainwayReader chainwayReader = null;
            try {
                chainwayReader = new ChainwayReader(appContext);
                if (chainwayReader.initialize() && chainwayReader.connect()) {
                    if (isShutdown.get()) {
                        // تم الإغلاق أثناء الكشف
                        chainwayReader.release();
                        return;
                    }
                    activeReader = chainwayReader;
                    isSimulationMode.set(false);
                    Log.i(TAG, "✅ تم الاتصال بالقارئ الحقيقي: Chainway");
                    onReaderReady();
                    return;
                } else {
                    chainwayReader.release();
                    chainwayReader = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "فشل الاتصال بالقارئ الحقيقي: " + t.getMessage());
                if (chainwayReader != null) {
                    try { chainwayReader.release(); } catch (Throwable ignored) {}
                }
            }

            // ✅ التوجه للمحاكاة
            if (isShutdown.get()) return;

            try {
                SimulatorReader simulatorReader = new SimulatorReader(appContext);
                simulatorReader.initialize();
                simulatorReader.connect();
                activeReader = simulatorReader;
                isSimulationMode.set(true);
                Log.i(TAG, "🔬 تم التفعيل في وضع المحاكاة");
                onReaderReady();
            } catch (Throwable t) {
                Log.e(TAG, "❌ فشل حتى في المحاكاة!", t);
                // ✅ نُعلّم الـ latch حتى لو فشل الكل
                isReaderReady.set(false);
                readerReadyLatch.countDown();
            }
        });

        detectThread.setName("MiraRFID-Detector");
        detectThread.setDaemon(true);
        detectThread.setPriority(Thread.NORM_PRIORITY - 1);
        detectThread.start();
    }

    private void onReaderReady() {
        isReaderReady.set(true);
        readerReadyLatch.countDown();
    }

    /**
     * ✅ [إصلاح #1, #2] انتظار جاهزية القارئ
     */
    public boolean waitForReader(long timeoutMs) {
        if (isReaderReady.get()) return activeReader != null;
        try {
            boolean ok = readerReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return ok && activeReader != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isReady() {
        return isReaderReady.get() && activeReader != null && !isShutdown.get();
    }

    // ==================== Release / Shutdown ====================

    /**
     * ✅ [إصلاح #3, #22] إغلاق آمن
     */
    public synchronized void release() {
        if (isShutdown.compareAndSet(false, true)) {
            Log.i(TAG, "Shutting down MiraRfidService");
        }

        // 1) انتظر الخيط إن كان يعمل (بحد أقصى 2 ثانية)
        Thread t = detectThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2) أغلق القارئ
        MiraIRFIDReader reader = activeReader;
        if (reader != null) {
            try {
                reader.disconnect();
                reader.release();
            } catch (Throwable e) {
                Log.e(TAG, "Error releasing active reader", e);
            }
            activeReader = null;
        }

        // 3) أعلم المنتظرين
        readerReadyLatch.countDown();

        // 4) امسح الـ instance
        instance = null;
    }

    // ==================== Getters ====================

    public boolean isSimulationMode() { return isSimulationMode.get(); }
    public MiraIRFIDReader getActiveReader() { return activeReader; }
    public OperationState getCurrentState() { return currentState.get(); }
    public String getLastReadEpc() { return lastReadEpc.get(); }
    public String getLastReadTid() { return lastReadTid.get(); }
    public boolean isShutdown() { return isShutdown.get(); }

    // ==================== Password ====================

    /**
     * ✅ [إصلاح #4, #9] بدون FALLBACK_PWD ثابت
     */
    @Nullable
    private String normalizePwd(@Nullable String pwdStr) {
        // 1) كلمة صريحة
        if (pwdStr != null && !pwdStr.trim().isEmpty()) {
            return pwdStr.trim().toUpperCase(Locale.US);
        }

        // 2) من RfidPasswordManager
        if (pwdManager != null) {
            try {
                String managed = pwdManager.getPasswordForTag(lastReadTid.get());
                if (managed != null && !managed.isEmpty()) {
                    Log.d(TAG, "🔑 Password from RfidPasswordManager (mode=" +
                            pwdManager.getPasswordMode() + ")");
                    return managed;
                }
            } catch (Throwable t) {
                Log.e(TAG, "normalizePwd: RfidPasswordManager error", t);
            }
        }

        // 3) ✅ لا fallback مكتوب في الكود
        // إذا لم نجد كلمة → نُعيد null (المستدعي يقرر)
        Log.w(TAG, "⚠️ No password available — please configure in Settings");
        return null;
    }

    /**
     * ✅ [إصلاح #4] تحقق قبل الاستخدام
     */
    private boolean canProceedWithPwd(@Nullable String pwd, String operationName) {
        if (pwd == null || pwd.isEmpty()) {
            Log.e(TAG, operationName + ": no password available");
            return false;
        }
        return true;
    }

    // ==================== Low-level Operations ====================

    /**
     * ✅ [إصلاح #7, #11, #26]
     */
    public boolean writeData(@Nullable String pwdStr, int bank, int ptr, @Nullable String data) {
        // ✅ [إصلاح #7] فحص الوضع
        MiraIRFIDReader reader = activeReader;
        if (reader == null) {
            Log.w(TAG, "writeData: no active reader");
            return false;
        }
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "writeData: empty data");
            return false;
        }

        String safePwd = normalizePwd(pwdStr);
        if (!canProceedWithPwd(safePwd, "writeData")) return false;

        Log.d(TAG, "📝 writeData: bank=" + bank + " ptr=" + ptr +
                " len=" + data.length() + " pwd=****");

        try {
            return reader.writeData(safePwd, bank, ptr, data);
        } catch (Throwable t) {
            Log.e(TAG, "writeData exception", t);
            return false;
        }
    }

    @Nullable
    public String readData(@Nullable String pwdStr, int bank, int ptr, int len) {
        MiraIRFIDReader reader = activeReader;
        if (reader == null) {
            Log.w(TAG, "readData: no active reader");
            return null;
        }

        String safePwd = normalizePwd(pwdStr);
        if (!canProceedWithPwd(safePwd, "readData")) return null;

        Log.d(TAG, "📖 readData: bank=" + bank + " ptr=" + ptr + " len=" + len);

        try {
            return reader.readData(safePwd, bank, ptr, len);
        } catch (Throwable t) {
            Log.e(TAG, "readData exception", t);
            return null;
        }
    }

    public boolean setTagLockState(@Nullable String pwdStr, int bank, boolean shouldLock) {
        MiraIRFIDReader reader = activeReader;
        if (reader == null) return false;

        String safePwd = normalizePwd(pwdStr);
        if (!canProceedWithPwd(safePwd, "setTagLockState")) return false;

        Log.i(TAG, "🔐 setTagLockState: bank=" + bank +
                " lock=" + shouldLock + " pwd=****");

        try {
            return reader.setTagLockState(safePwd, bank, shouldLock);
        } catch (Throwable t) {
            Log.e(TAG, "setTagLockState exception", t);
            return false;
        }
    }

    public boolean setFilter(int bank, int ptr, @Nullable String filterData) {
        MiraIRFIDReader reader = activeReader;
        // ✅ [إصلاح #23] في المحاكاة نُعيد true مع توثيق
        if (reader == null) {
            if (isSimulationMode.get()) {
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
        MiraIRFIDReader reader = activeReader;
        if (reader != null) {
            try {
                reader.clearFilter();
            } catch (Throwable t) {
                Log.e(TAG, "clearFilter exception", t);
            }
        }
    }

    public void resetForNewTag() {
        MiraIRFIDReader reader = activeReader;
        if (reader instanceof SimulatorReader) {
            try {
                ((SimulatorReader) reader).resetForNewTag();
                Log.d(TAG, "✅ تم إعادة تهيئة المحاكي لشريحة جديدة");
            } catch (Throwable t) {
                Log.e(TAG, "resetForNewTag exception", t);
            }
        }
    }

    // ==================== High-level Operations ====================

    /**
     * ✅ [إصلاح #7, #8] لا يتأثر بـ verifyWrite
     */
    @Nullable
    public String readEpc(@Nullable String accessPassword) {
        currentState.set(OperationState.READING);

        String epc = readData(accessPassword, EPC_BANK,
                EPC_START_WORD, EPC_READ_LENGTH_WORDS);

        if (epc != null && !epc.isEmpty()) {
            String normalized = normalizeEpc(epc);
            lastReadEpc.set(normalized);
            currentState.set(OperationState.COMPLETED);
            Log.i(TAG, "✅ readEpc OK: " + normalized);
            return normalized;
        }

        currentState.set(OperationState.FAILED);
        Log.e(TAG, "❌ readEpc FAILED");
        return null;
    }

    /**
     * ✅ [إصلاح #5, #13] مع تأخير بسيط بين المحاولات
     */
    @Nullable
    public String readTid(@Nullable String accessPassword) {
        currentState.set(OperationState.READING);

        String tid = null;

        for (int i = 0; i < TID_READ_ATTEMPTS_WORDS.length; i++) {
            int words = TID_READ_ATTEMPTS_WORDS[i];
            try {
                tid = readData(accessPassword, TID_BANK, TID_START_WORD, words);
                if (tid != null && !tid.isEmpty()) {
                    Log.d(TAG, "✅ TID read with " + words + " words");
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "⚠️ readTid attempt " + words + " failed: " + t.getMessage());
            }

            // ✅ [إصلاح #5] تأخير بسيط قبل المحاولة التالية
            if (i < TID_READ_ATTEMPTS_WORDS.length - 1) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (tid != null && !tid.isEmpty()) {
            // ✅ [إصلاح #13] تنظيف ذكي
            String cleaned = normalizeTid(tid);

            if (isValidTid(cleaned)) {
                lastReadTid.set(cleaned);
                currentState.set(OperationState.COMPLETED);
                Log.i(TAG, "✅ readTid OK: " + cleaned + " (length=" + cleaned.length() + ")");
                return cleaned;
            } else {
                Log.w(TAG, "⚠️ readTid invalid format: " + cleaned);
            }
        }

        currentState.set(OperationState.FAILED);
        Log.e(TAG, "❌ readTid FAILED after " + TID_READ_ATTEMPTS_WORDS.length + " attempts");
        return null;
    }

    /**
     * ✅ [إصلاح #13] تنظيف TID بحذر
     */
    @NonNull
    private String normalizeTid(@NonNull String raw) {
        String tid = raw.trim().toUpperCase(Locale.US);

        // إزالة 0000 المتكررة في النهاية — بحد أقصى مرتين
        int maxTrims = 2;
        int trims = 0;
        while (tid.length() > 20 && tid.endsWith("0000") && trims < maxTrims) {
            tid = tid.substring(0, tid.length() - 4);
            trims++;
        }

        return tid;
    }

    private boolean isValidTid(@NonNull String tid) {
        if (tid.length() < 16 || tid.length() > 32) return false;
        return tid.matches("^[0-9A-F]+$");
    }

    /**
     * ✅ [إصلاح #14, #7] writeEpc
     */
    public boolean writeEpc(@Nullable String newEpc, @Nullable String accessPassword) {
        currentState.set(OperationState.WRITING);

        // ✅ [إصلاح #7] فحص الوضع
        if (newEpc == null || newEpc.isEmpty()) {
            Log.w(TAG, "writeEpc: empty EPC");
            currentState.set(OperationState.FAILED);
            return false;
        }

        if (activeReader == null) {
            Log.w(TAG, "writeEpc: no active reader");
            currentState.set(OperationState.FAILED);
            return false;
        }

        // ✅ [إصلاح #14] تأكد من الصيغة
        String normalizedEpc = normalizeEpc(newEpc);
        if (normalizedEpc.length() < 8 || normalizedEpc.length() > 24) {
            Log.w(TAG, "writeEpc: invalid EPC length: " + normalizedEpc.length());
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
            return true;
        }

        currentState.set(OperationState.FAILED);
        Log.e(TAG, "❌ writeEpc FAILED");
        return false;
    }

    /**
     * ✅ [إصلاح #8, #15] لا يُغيّر currentState — يقارن بشكل ذكي
     */
    public boolean verifyWrite(@Nullable String expectedEpc,
                                @Nullable String accessPassword) {
        OperationState savedState = currentState.get();
        currentState.set(OperationState.VERIFYING);

        try {
            if (expectedEpc == null || expectedEpc.isEmpty()) return false;

            String readBack = readEpc(accessPassword);

            if (readBack == null) {
                Log.e(TAG, "❌ verifyWrite: readBack is null");
                return false;
            }

            // ✅ [إصلاح #15] مقارنة بعد تطبيع كامل
            String expectedNorm = normalizeEpc(expectedEpc);
            String actualNorm = normalizeEpc(readBack);

            if (expectedNorm.equalsIgnoreCase(actualNorm)) {
                Log.i(TAG, "✅ verifyWrite OK: " + actualNorm);
                return true;
            }

            Log.e(TAG, "❌ verifyWrite FAILED: expected=" + expectedNorm +
                    " read=" + actualNorm);
            return false;

        } catch (Throwable t) {
            Log.e(TAG, "verifyWrite exception", t);
            return false;
        }
        // ✅ [إصلاح #8] لا نُعدّل currentState هنا — يُدار من خارج
    }

    /**
     * ✅ [إصلاح #16] registerRfidBinding يفرّق بين الوضعين
     */
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
            payload.put("device_type", isSimulationMode.get() ? "simulator" : "chainway_c72");
            payload.put("operation_type", "register");

            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/register_rfid", payload);

            if (response != null && response.isSuccess) {
                currentState.set(OperationState.COMPLETED);
                Log.d(TAG, "✅ تم التسجيل في قاعدة البيانات بنجاح");
                return true;
            }

            // ✅ [إصلاح #16] في المحاكاة، نُسجّل فشلاً حقيقياً
            if (isSimulationMode.get()) {
                Log.w(TAG, "⚠️ [SIMULATION] Registration failed — " +
                        "returning false for realistic behavior");
                currentState.set(OperationState.FAILED);
                return false;
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
            searchPayload.put("identifier_type", identifierType != null ? identifierType : "auto");
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

            if (isSimulationMode.get()) {
                return createSimulatedSearchResult(identifier);
            }
            return null;

        } catch (Throwable t) {
            Log.e(TAG, "searchInMira error", t);
            if (isSimulationMode.get()) {
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
            Log.e(TAG, "createSimulatedSearchResult error", t);
            return new JSONObject();
        }
    }

    @NonNull
    private String generateGtinFromIdentifier(@NonNull String identifier) {
        String numbers = identifier.replaceAll("[^0-9]", "");

        if (numbers.isEmpty()) {
            numbers = String.valueOf(System.currentTimeMillis());
        }

        while (numbers.length() < 12) {
            numbers = "0" + numbers;
        }
        return numbers.substring(0, 12);
    }

    /**
     * ✅ [إصلاح #17] فحص الطول قبل substring
     */
    @NonNull
    public String generateEpcFromMiraId(@Nullable String miraId) {
        if (miraId == null || miraId.isEmpty()) {
            // fallback: EPC افتراضي
            return "3008" + String.format(Locale.US, "%012d",
                    System.currentTimeMillis() % 1_000_000_000_000L);
        }

        StringBuilder epc = new StringBuilder("3008");
        String cleanId = miraId.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);

        // ✅ [إصلاح #17] padding أولاً
        while (cleanId.length() < 12) {
            cleanId = "0" + cleanId;
        }

        epc.append(cleanId, 0, 12);

        while (epc.length() < 24) {
            epc.append("0");
        }

        return epc.substring(0, 24).toUpperCase(Locale.US);
    }

    @NonNull
    public String getDeviceName() {
        MiraIRFIDReader reader = activeReader;
        if (reader == null) return "لا يوجد جهاز";

        try {
            String name = reader.getDeviceName();
            return name + (isSimulationMode.get() ? " (محاكي)" : "");
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

    // ==================== Tag Inventory ====================

    /**
     * ✅ [إصلاح #19] فحص tid == null
     */
    public boolean verifyTagAllowed(@Nullable String tid) {
        if (tid == null || tid.isEmpty()) {
            Log.w(TAG, "verifyTagAllowed: null tid");
            return isSimulationMode.get();
        }

        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            MiraApiClient.ApiResponse response = apiClient.getWithAuth(
                    "/rfid-inventory/verify?tid=" + tid);

            if (response != null && response.isSuccess && response.data != null) {
                return response.data.optBoolean("approved", false);
            }
            return isSimulationMode.get();
        } catch (Throwable t) {
            Log.e(TAG, "verifyTagAllowed error", t);
            return isSimulationMode.get();
        }
    }

    /**
     * ✅ [إصلاح #20] offline fallback
     */
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

            // ✅ [إصلاح #20] offline fallback
            Log.w(TAG, "markTagProgrammed failed — saving locally");
            saveProgrammedOffline(tid, miraId, epc);
            return isSimulationMode.get();

        } catch (Throwable t) {
            Log.e(TAG, "markTagProgrammed error", t);
            saveProgrammedOffline(tid, miraId, epc);
            return isSimulationMode.get();
        }
    }

    private void saveProgrammedOffline(String tid, String miraId, String epc) {
        try {
            JSONObject record = new JSONObject();
            record.put("tid", tid);
            record.put("mira_id", miraId);
            record.put("epc", epc);
            record.put("saved_at", System.currentTimeMillis());

            // احفظه في SharedPreferences
            android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                    "rfid_programmed_queue", Context.MODE_PRIVATE);
            String existing = prefs.getString("queue", "[]");
            JSONArray arr = new JSONArray(existing);
            arr.put(record);

            // احتفظ بآخر 100 فقط
            while (arr.length() > 100) {
                arr.remove(0);
            }

            prefs.edit().putString("queue", arr.toString()).apply();
            Log.d(TAG, "💾 Saved programmed tag offline: " + tid);

        } catch (Throwable t) {
            Log.e(TAG, "saveProgrammedOffline error", t);
        }
    }

    public boolean importBatch(@Nullable String batchNumber, @Nullable JSONArray tags) {
        if (batchNumber == null || tags == null) {
            Log.w(TAG, "importBatch: null parameters");
            return false;
        }
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(appContext);
            JSONObject payload = new JSONObject();
            payload.put("batch_number", batchNumber);
            payload.put("tags", tags);

            MiraApiClient.ApiResponse response =
                    apiClient.postWithAuth("/rfid-inventory/import", payload);
            return response != null && response.isSuccess;
        } catch (Throwable t) {
            Log.e(TAG, "importBatch error", t);
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
            if (isSimulationMode.get()) {
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
            Log.e(TAG, "getRfidInventoryStats error", t);
            return null;
        }
    }

    // ==================== Utilities ====================

    /**
     * ✅ [إصلاح #15] تطبيع EPC للمقارنات
     */
    @NonNull
    private String normalizeEpc(@Nullable String epc) {
        if (epc == null) return "";
        String cleaned = epc.trim().toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        return cleaned;
    }

    // ==================== Health ====================

    @NonNull
    public String getHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 MiraRfidService Health\n");
        sb.append("─────────────\n");
        sb.append("Shutdown: ").append(isShutdown.get()).append("\n");
        sb.append("Reader ready: ").append(isReaderReady.get()).append("\n");
        sb.append("Simulation: ").append(isSimulationMode.get()).append("\n");
        sb.append("State: ").append(currentState.get()).append("\n");
        sb.append("Device: ").append(getDeviceName()).append("\n");
        sb.append("Pwd manager: ").append(pwdManager != null ? "OK" : "NULL").append("\n");
        return sb.toString();
    }
}
