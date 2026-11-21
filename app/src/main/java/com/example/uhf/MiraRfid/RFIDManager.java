package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.example.uhf.tools.DeviceDetector;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.Gen2Entity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  RFIDManager v4.1.0 — State Machine + Dense Jewelry Session S1 + Backward Compatibility
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class RFIDManager {

    private static final String TAG = "RFIDManager";
    private static final String PREFS_NAME = "mira_rfid_devices";
    private static final String KEY_DEVICES = "saved_devices";

    private static final int DEFAULT_POWER_DBM = 30;
    private static final int DEFAULT_RSSI_THRESHOLD = -75;
    private static final int DEFAULT_SCAN_INTERVAL_MS = 100;

    // ═══════════════════════════════════════════════════════════
    //  🎯 State Machine
    // ═══════════════════════════════════════════════════════════
    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        SCANNING,
        ERROR
    }

    public interface ReadyCallback {
        void onReady(MiraIRFIDReader reader);
        void onError(String reason);
    }

    public interface StateListener {
        void onStateChanged(State oldState, State newState);
    }

    // ═══════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════
    private static volatile RFIDManager instance;

    public static RFIDManager getInstance(Context context) {
        if (instance == null) {
            synchronized (RFIDManager.class) {
                if (instance == null) {
                    Context appCtx = context.getApplicationContext();
                    instance = new RFIDManager(appCtx);
                    RFIDReaderFactory.init(appCtx);
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════
    //  الحقول
    // ═══════════════════════════════════════════════════════════
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService initExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RFID-Init");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<State> state =
            new AtomicReference<>(State.UNINITIALIZED);

    private final List<ReadyCallback> pendingReadyCallbacks =
            new CopyOnWriteArrayList<>();
    private final List<StateListener> stateListeners =
            new CopyOnWriteArrayList<>();

    private final List<MiraIRFIDReader.OnTagReadListener> tagReadListeners =
            new CopyOnWriteArrayList<>();

    private final MiraIRFIDReader.OnTagReadListener compositeListener =
            new MiraIRFIDReader.OnTagReadListener() {
        @Override
        public void onTagRead(String epc, int rssi) {
            for (MiraIRFIDReader.OnTagReadListener l : tagReadListeners) {
                try { l.onTagRead(epc, rssi); }
                catch (Exception e) { Log.e(TAG, "listener.onTagRead error", e); }
            }
        }
        @Override
        public void onScanStarted() {
            for (MiraIRFIDReader.OnTagReadListener l : tagReadListeners) {
                try { l.onScanStarted(); }
                catch (Exception ignored) {}
            }
        }
        @Override
        public void onScanStopped() {
            for (MiraIRFIDReader.OnTagReadListener l : tagReadListeners) {
                try { l.onScanStopped(); }
                catch (Exception ignored) {}
            }
        }
        @Override
        public void onConnectionChanged(boolean connected) {
            for (MiraIRFIDReader.OnTagReadListener l : tagReadListeners) {
                try { l.onConnectionChanged(connected); }
                catch (Exception ignored) {}
            }
        }
        @Override
        public void onError(String error) {
            for (MiraIRFIDReader.OnTagReadListener l : tagReadListeners) {
                try { l.onError(error); }
                catch (Exception ignored) {}
            }
        }
    };

    private volatile MiraIRFIDReader activeReader;
    private volatile RFIDDevice activeDevice;
    private volatile boolean denseJewelrySessionActive = false;

    private RFIDManager(Context context) {
        this.context = context;
    }

    // ═══════════════════════════════════════════════════════════
    //  🎯 Public API
    // ═══════════════════════════════════════════════════════════

    public State getState() {
        return state.get();
    }

    public boolean isReady() {
        State s = state.get();
        return (s == State.READY || s == State.SCANNING) && activeReader != null;
    }

    public boolean isScanning() {
        return state.get() == State.SCANNING;
    }

    public void ensureReady(ReadyCallback callback) {
        if (callback == null) return;

        State current = state.get();

        if (current == State.READY || current == State.SCANNING) {
            MiraIRFIDReader reader = activeReader;
            if (reader != null) {
                mainHandler.post(() -> safeOnReady(callback, reader));
                return;
            }
            Log.w(TAG, "⚠️ State=" + current + " but reader=null — resetting");
            state.set(State.UNINITIALIZED);
            current = State.UNINITIALIZED;
        }

        if (current == State.INITIALIZING) {
            Log.d(TAG, "⏳ Init in progress — queueing callback");
            pendingReadyCallbacks.add(callback);
            return;
        }

        Log.i(TAG, "🚀 Starting initialization (state=" + current + ")");
        pendingReadyCallbacks.add(callback);

        if (current == State.UNINITIALIZED) {
            if (!state.compareAndSet(State.UNINITIALIZED, State.INITIALIZING)) {
                return;
            }
        } else if (current == State.ERROR) {
            if (!state.compareAndSet(State.ERROR, State.INITIALIZING)) {
                return;
            }
        } else {
            return;
        }

        notifyStateChanged(current, State.INITIALIZING);
        initExecutor.execute(this::runInitialization);
    }

    public boolean ensureReadySync(long timeoutMs) {
        if (isReady()) return true;

        final Object lock = new Object();
        final boolean[] result = {false};
        final boolean[] done = {false};

        ensureReady(new ReadyCallback() {
            @Override public void onReady(MiraIRFIDReader reader) {
                synchronized (lock) {
                    result[0] = true;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
            @Override public void onError(String reason) {
                synchronized (lock) {
                    result[0] = false;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { lock.wait(remaining); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result[0];
    }

    public void addStateListener(StateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public MiraIRFIDReader getActiveReader() {
        if (!isReady()) return null;
        return activeReader;
    }

    public RFIDWithUHFUART getRawSdkReader() {
        try {
            return BaseTabFragmentActivity.getSharedReader();
        } catch (Exception e) {
            Log.w(TAG, "getRawSdkReader error: " + e.getMessage());
            return null;
        }
    }

    /**
     * ضبط الجلسة S1 والهدف A لمنع تداخل الشرائح المتلاصقة في صواني المجوهرات
     * وتثبيتها تلقائياً حتى لو تأخرت تهيئة الـ SDK.
     */
    public boolean configureDenseJewelrySession() {
        denseJewelrySessionActive = true;
        return applyGen2SessionS1TargetA();
    }

    public void clearDenseJewelrySession() {
        denseJewelrySessionActive = false;
    }

    private boolean applyGen2SessionS1TargetA() {
        try {
            RFIDWithUHFUART reader = getRawSdkReader();
            if (reader == null) return false;

            Gen2Entity gen2 = reader.getGen2();
            if (gen2 == null) {
                gen2 = new Gen2Entity();
            }
            gen2.setQuerySession(1); // Session S1
            gen2.setQueryTarget(0);  // Target A
            boolean applied = reader.setGen2(gen2);
            if (applied) {
                Log.i(TAG, "✅ Dense Jewelry Gen2 applied: Session S1 / Target A");
            }
            return applied;
        } catch (Throwable t) {
            Log.w(TAG, "applyGen2SessionS1TargetA skipped: " + t.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🔧 التنفيذ الداخلي
    // ═══════════════════════════════════════════════════════════

    private void runInitialization() {
        boolean success = false;
        try {
            success = doInitialize();
        } catch (Throwable t) {
            Log.e(TAG, "❌ Initialization crash", t);
        }
        final boolean finalSuccess = success;
        mainHandler.post(() -> onInitializationComplete(finalSuccess));
    }

    private boolean doInitialize() {
        Log.i(TAG, "🔧 doInitialize: detecting hardware...");

        if (!DeviceDetector.isRfidHardwareSupported()) {
            Log.w(TAG, "⚠️ Hardware not supported");
            return false;
        }

        RFIDDevice device = getOrCreateActiveDevice();
        if (device == null
                || device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
            Log.w(TAG, "⚠️ No real device available");
            return false;
        }
        activeDevice = device;

        if (activeReader == null) {
            activeReader = RFIDReaderFactory.create(
                    device.getType(), device.getAddress());
            if (activeReader == null) {
                Log.e(TAG, "❌ Factory returned null");
                return false;
            }
        }

        activeReader.setOnTagReadListener(compositeListener);

        if (!activeReader.isConnected()) {
            Log.i(TAG, "🔧 Calling reader.initialize()...");
            if (!activeReader.initialize()) {
                Log.e(TAG, "❌ initialize() failed");
                return false;
            }

            Log.i(TAG, "🔧 Calling reader.connect()...");
            if (!activeReader.connect()) {
                Log.e(TAG, "❌ connect() failed");
                return false;
            }
        }

        try {
            int power = MiraReaderSettings.getInstance(context).getPowerDbm();
            activeReader.setPower(power);
            Log.i(TAG, "✅ [B1] Power applied: " + power + " dBm");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ [B1] Power apply failed: " + e.getMessage());
        }

        applyMiraReaderSettingsWithRetry(0);

        Log.i(TAG, "✅ Initialization SUCCESS");
        return true;
    }

    private void onInitializationComplete(boolean success) {
        State old = state.get();
        State newState = success ? State.READY : State.ERROR;
        state.set(newState);
        notifyStateChanged(old, newState);

        List<ReadyCallback> queue = new ArrayList<>(pendingReadyCallbacks);
        pendingReadyCallbacks.clear();

        MiraIRFIDReader reader = activeReader;
        Log.i(TAG, (success ? "✅" : "❌")
                + " Flushing " + queue.size() + " pending callbacks");

        for (ReadyCallback cb : queue) {
            if (success) safeOnReady(cb, reader);
            else         safeOnError(cb, "فشل تهيئة القارئ");
        }
    }

    private void safeOnReady(ReadyCallback cb, MiraIRFIDReader reader) {
        try { cb.onReady(reader); }
        catch (Exception e) { Log.e(TAG, "callback.onReady error", e); }
    }

    private void safeOnError(ReadyCallback cb, String reason) {
        try { cb.onError(reason); }
        catch (Exception e) { Log.e(TAG, "callback.onError error", e); }
    }

    private void notifyStateChanged(State from, State to) {
        for (StateListener l : stateListeners) {
            try { l.onStateChanged(from, to); }
            catch (Exception e) { Log.e(TAG, "stateListener error", e); }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  📡 Scan API
    // ═══════════════════════════════════════════════════════════

    public void addListener(@NonNull MiraIRFIDReader.OnTagReadListener listener) {
        if (listener == null) return;
        if (!tagReadListeners.contains(listener)) {
            tagReadListeners.add(listener);
            Log.d(TAG, "➕ Listener added. Total=" + tagReadListeners.size());
        }
        ensureCompositeAttached();
    }

    public void removeListener(@Nullable MiraIRFIDReader.OnTagReadListener listener) {
        if (listener == null) return;
        if (tagReadListeners.remove(listener)) {
            Log.d(TAG, "➖ Listener removed. Total=" + tagReadListeners.size());
        }
    }

    @Deprecated
    public void setListener(MiraIRFIDReader.OnTagReadListener listener) {
        if (listener != null) {
            addListener(listener);
            Log.d(TAG, "⚠️ Deprecated setListener → delegated to addListener");
        }
    }

    private void ensureCompositeAttached() {
        if (activeReader != null) {
            activeReader.setOnTagReadListener(compositeListener);
        }
    }

    public void startScan(final ReadyCallback callback) {
        if (callback == null) return;
        ensureReady(new ReadyCallback() {
            @Override public void onReady(MiraIRFIDReader reader) {
                if (!state.compareAndSet(State.READY, State.SCANNING)) {
                    safeOnError(callback, "الحالة ليست READY");
                    return;
                }
                if (denseJewelrySessionActive) {
                    applyGen2SessionS1TargetA();
                }
                boolean ok;
                try {
                    ok = reader.startScan();
                } catch (Exception e) {
                    Log.e(TAG, "reader.startScan() exception", e);
                    ok = false;
                }
                if (!ok) {
                    state.set(State.READY);
                    safeOnError(callback, "startScan() أعاد false");
                    return;
                }
                safeOnReady(callback, reader);
            }
            @Override public void onError(String reason) {
                safeOnError(callback, reason);
            }
        });
    }

    @Deprecated
    public boolean startScan() {
        boolean wasReady = isReady();

        startScan(new ReadyCallback() {
            @Override
            public void onReady(MiraIRFIDReader reader) {
                Log.d(TAG, "✅ [legacy startScan] reader ready");
            }
            @Override
            public void onError(String reason) {
                Log.w(TAG, "⚠️ [legacy startScan] failed: " + reason);
            }
        });

        return wasReady;
    }

    public void stopScan() {
        if (!state.compareAndSet(State.SCANNING, State.READY)) return;
        if (activeReader != null) {
            try { activeReader.stopScan(); }
            catch (Exception e) { Log.e(TAG, "stopScan error", e); }
        }
    }

    public void reset() {
        stopScan();
        if (activeReader != null) {
            try { activeReader.release(); }
            catch (Exception ignored) {}
            activeReader = null;
        }
        activeDevice = null;
        pendingReadyCallbacks.clear();
        State old = state.getAndSet(State.UNINITIALIZED);
        notifyStateChanged(old, State.UNINITIALIZED);
    }

    // ═══════════════════════════════════════════════════════════
    //  💾 Device Management
    // ═══════════════════════════════════════════════════════════

    public RFIDDevice getActiveDevice() {
        return getOrCreateActiveDevice();
    }

    private RFIDDevice getOrCreateActiveDevice() {
        if (activeDevice != null) return activeDevice;

        MiraIRFIDReader.ReaderType detected = detectDeviceType();
        boolean hasRealHardware = (detected != MiraIRFIDReader.ReaderType.SIMULATOR);

        if (hasRealHardware) {
            List<RFIDDevice> devices = getAllDevices();
            for (RFIDDevice d : devices) {
                if (d.getType() == detected) {
                    activeDevice = d;
                    return activeDevice;
                }
            }
            activeDevice = new RFIDDevice(detected, "");
            activeDevice.setDefault(true);
            activeDevice.setPowerDbm(DEFAULT_POWER_DBM);
            activeDevice.setRssiThreshold(DEFAULT_RSSI_THRESHOLD);
            activeDevice.setScanIntervalMs(DEFAULT_SCAN_INTERVAL_MS);
            saveDevice(activeDevice);
            return activeDevice;
        }

        if (com.example.uhf.BuildConfig.ALLOW_SIMULATOR) {
            Log.w(TAG, "🧪 SIMULATOR allowed by build config");
            activeDevice = new RFIDDevice(
                    MiraIRFIDReader.ReaderType.SIMULATOR, "");
            activeDevice.setDefault(true);
            return activeDevice;
        }

        Log.e(TAG, "❌ No reader available");
        return null;
    }

    public MiraIRFIDReader.ReaderType detectDeviceType() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null
                ? Build.MANUFACTURER.toUpperCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toUpperCase() : "";
        String device = Build.DEVICE != null ? Build.DEVICE.toUpperCase() : "";

        if (model.contains("C72")) return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
        if (model.contains("C61")) return MiraIRFIDReader.ReaderType.CHAINWAY_C61;
        if (model.contains("C66")) return MiraIRFIDReader.ReaderType.CHAINWAY_C66;
        if (model.contains("C71")) return MiraIRFIDReader.ReaderType.CHAINWAY_C71;
        if (model.contains("VH-C83") || model.contains("C83")
                || device.contains("C83"))
            return MiraIRFIDReader.ReaderType.CHAINWAY_C72;

        if (model.contains("MC33") || model.contains("MC32")
                || model.contains("TC51") || model.contains("TC52"))
            return MiraIRFIDReader.ReaderType.ZEBRA_MC3300;

        if (model.contains("EDA5") || model.contains("CT40")
                || model.contains("CT60"))
            return MiraIRFIDReader.ReaderType.HONEYWELL_EDA50;

        if (model.contains("RP90") || model.contains("PA70"))
            return MiraIRFIDReader.ReaderType.UNITECH_RP902;

        if (manufacturer.contains("CHAINWAY") || brand.contains("CHAINWAY"))
            return MiraIRFIDReader.ReaderType.CHAINWAY_C72;

        if (manufacturer.contains("VANCH") || brand.contains("VANCH")
                || manufacturer.contains("VANQUAN") || brand.contains("VANQUAN"))
            return MiraIRFIDReader.ReaderType.CHAINWAY_C72;

        Log.w(TAG, "⚠️ Unknown device: " + model + " → SIMULATOR");
        return MiraIRFIDReader.ReaderType.SIMULATOR;
    }

    public boolean saveDevice(RFIDDevice device) {
        try {
            List<RFIDDevice> devices = getAllDevices();
            final String addr = device.getAddress();
            devices.removeIf(d ->
                    d.getType() == device.getType()
                            && (addr == null || addr.equals(d.getAddress())));
            devices.add(0, device);
            return saveDevices(devices);
        } catch (Exception e) {
            Log.e(TAG, "saveDevice error: " + e.getMessage());
            return false;
        }
    }

    private boolean saveDevices(List<RFIDDevice> devices) {
        try {
            JSONArray arr = new JSONArray();
            for (RFIDDevice d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("type", d.getType() != null ? d.getType().name() : "");
                obj.put("address", d.getAddress() != null ? d.getAddress() : "");
                obj.put("custom_name",
                        d.getCustomName() != null ? d.getCustomName() : "");
                obj.put("power", d.getPowerDbm());
                obj.put("rssi", d.getRssiThreshold());
                obj.put("interval", d.getScanIntervalMs());
                obj.put("is_default", d.isDefault());
                obj.put("last_used", d.getLastUsedAt());
                arr.put(obj);
            }
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveDevices error: " + e.getMessage());
            return false;
        }
    }

    public List<RFIDDevice> getAllDevices() {
        List<RFIDDevice> devices = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_DEVICES, "[]");
            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                RFIDDevice device = new RFIDDevice();

                String typeName = obj.optString("type", "");
                if (!typeName.isEmpty()) {
                    try {
                        device.setType(MiraIRFIDReader.ReaderType.valueOf(typeName));
                    } catch (Exception ignored) {}
                }

                device.setAddress(obj.optString("address", ""));
                device.setCustomName(obj.optString("custom_name", ""));
                device.setPowerDbm(obj.optInt("power", DEFAULT_POWER_DBM));
                device.setRssiThreshold(obj.optInt("rssi", DEFAULT_RSSI_THRESHOLD));
                device.setScanIntervalMs(obj.optInt("interval", DEFAULT_SCAN_INTERVAL_MS));
                device.setDefault(obj.optBoolean("is_default", false));
                device.setLastUsedAt(obj.optLong("last_used", 0));

                devices.add(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllDevices error: " + e.getMessage());
        }
        return devices;
    }

    public void deleteDevice(RFIDDevice device) {
        if (device == null) return;
        List<RFIDDevice> devices = getAllDevices();
        devices.removeIf(d ->
                (d.getType() == device.getType() && Objects.equals(d.getAddress(), device.getAddress()))
                || d.toString().equals(device.toString()));
        saveDevices(devices);
    }

    public boolean setActiveDevice(RFIDDevice device) {
        if (device == null) return false;

        if (activeReader != null) {
            try { activeReader.release(); }
            catch (Exception ignored) {}
            activeReader = null;
        }

        this.activeDevice = device;
        device.setLastUsedAt(System.currentTimeMillis());
        saveDevice(device);

        State old = state.getAndSet(State.UNINITIALIZED);
        notifyStateChanged(old, State.UNINITIALIZED);

        return true;
    }

    // ═══════════════════════════════════════════════════════════
    //  ⚡ Power Control
    // ═══════════════════════════════════════════════════════════

    public boolean setPower(int dbm) {
        try {
            if (dbm < 5 || dbm > 33) return false;

            MiraReaderSettings.getInstance(context).setPowerDbm(dbm);

            MiraIRFIDReader reader = activeReader;
            if (reader != null && reader.isConnected()) {
                reader.setPower(dbm);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setPower error", e);
            return false;
        }
    }

    public int getPower() {
        return MiraReaderSettings.getInstance(context).getPowerDbm();
    }

    public void applyMiraReaderSettings() {
        try {
            RFIDWithUHFUART sdkReader = getRawSdkReader();
            if (sdkReader != null) {
                MiraReaderSettings.getInstance(context).applyToReader(sdkReader);
                if (denseJewelrySessionActive) {
                    applyGen2SessionS1TargetA();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyMiraReaderSettings error", e);
        }
    }

    private void applyMiraReaderSettingsWithRetry(int attempt) {
        final int MAX_ATTEMPTS = 3;
        final MiraReaderSettings settings = MiraReaderSettings.getInstance(context);

        try {
            RFIDWithUHFUART sdkReader = getRawSdkReader();

            if (sdkReader != null) {
                MiraReaderSettings.ApplyResult result = settings.applyToReader(sdkReader);
                if (denseJewelrySessionActive) {
                    applyGen2SessionS1TargetA();
                }
                if (result.isSuccess()) {
                    Log.i(TAG, "✅ [B1] Full settings applied ("
                            + result.applied + " items)");
                    return;
                }
                Log.w(TAG, "⚠️ [B1] Partial failure: " + result.failedNames);
            } else {
                Log.d(TAG, "⏳ [B1] Raw SDK not ready yet (attempt "
                        + attempt + "/" + MAX_ATTEMPTS + ")");
            }

            if (attempt < MAX_ATTEMPTS) {
                final int next = attempt + 1;
                mainHandler.postDelayed(() -> {
                    Log.d(TAG, "🔄 [B1] Retry " + next + "/" + MAX_ATTEMPTS);
                    applyMiraReaderSettingsWithRetry(next);
                }, 800L * next);
            } else {
                Log.w(TAG, "⚠️ [B1] Max attempts reached — Power only");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ [B1] applyMiraReaderSettingsWithRetry error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ Backward-Compatibility Layer
    // ═══════════════════════════════════════════════════════════

    @Deprecated
    public void release() {
        Log.d(TAG, "⚠️ [legacy release] → delegating to reset()");
        reset();
    }

    @Deprecated
    public boolean testConnection(RFIDDevice device) {
        if (device == null) {
            Log.w(TAG, "⚠️ testConnection: null device");
            return false;
        }
        try {
            return isReady();
        } catch (Exception e) {
            Log.e(TAG, "testConnection error", e);
            return false;
        }
    }

    @Deprecated
    public boolean waitForReaderReady(long timeoutMs) {
        return ensureReadySync(timeoutMs);
    }

    @Deprecated
    public boolean isRealReaderConnected() {
        return isReady();
    }
}
