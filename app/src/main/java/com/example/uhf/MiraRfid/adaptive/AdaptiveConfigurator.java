package com.example.uhf.MiraRfid.adaptive;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * ✅ AdaptiveConfigurator — العقل المدبر للتكيّف
 * 
 * يقرر:
 *   1. Region (FCC/ETSI)
 *   2. Power (تلقائياً حسب القارئ والوسم)
 *   3. Session / Target / Q
 *   4. يتعلّم من النجاح/الفشل ويحفظ الإعدادات المثلى
 */
public class AdaptiveConfigurator {

    private static final String TAG = "AdaptiveConfigurator";
    private static final String PREFS = "mira_adaptive_rfid";

    private final Context context;
    private final SharedPreferences prefs;

    private ReaderCapabilities readerCaps;
    private TagProfiler lastTag;

    private final Map<String, Integer> successCount = new HashMap<>();
    private final Map<String, Integer> failureCount = new HashMap<>();

    // ==================== Constructor ====================

    public AdaptiveConfigurator(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadLearnedSettings();
    }

    // ==================== Initial Setup ====================

    public void initializeReader() {
        readerCaps = new ReaderCapabilities();
        readerCaps.detectManufacturer();
        readerCaps.inferPreferredRegion();

        Log.i(TAG, "✅ Reader initialized: " + readerCaps);
    }

    public int getOptimalFrequencyMode() {
        if (readerCaps == null) {
            initializeReader();
        }
        return readerCaps.getFrequencyModeCode();
    }

    public int getOptimalPower() {
        if (readerCaps == null) {
            initializeReader();
        }

        int basePower = readerCaps.defaultPowerDbm;

        if (lastTag != null) {
            if (lastTag.rssi < -75) {
                return readerCaps.maxPowerDbm;
            } else if (lastTag.rssi < -60) {
                return Math.min(readerCaps.maxPowerDbm, basePower + 3);
            } else if (lastTag.rssi > -45) {
                return Math.max(readerCaps.minPowerDbm, basePower - 3);
            }
        }

        return basePower;
    }

    public void updateLastTag(String tid, int rssi) {
        this.lastTag = new TagProfiler(tid, rssi);
        Log.d(TAG, "🔄 Last tag updated: " + lastTag);
    }

    public int getOptimalSession() {
        if (lastTag != null) {
            return lastTag.recommendedSession;
        }
        return 1;
    }

    public int getOptimalQValue() {
        if (lastTag != null) {
            return lastTag.recommendedQ;
        }
        return 4;
    }

    public void recordSuccess(String operation, String tagVendor) {
        String key = operation + "|" + tagVendor;
        int count = successCount.getOrDefault(key, 0) + 1;
        successCount.put(key, count);
        persistLearnedSettings(key, "success", count);
        Log.d(TAG, "✅ Success recorded: " + key + " → " + count);
    }

    public void recordFailure(String operation, String tagVendor, String reason) {
        String key = operation + "|" + tagVendor;
        int count = failureCount.getOrDefault(key, 0) + 1;
        failureCount.put(key, count);
        persistLearnedSettings(key + "|reason=" + reason, "failure", count);
        Log.w(TAG, "❌ Failure recorded: " + key + " reason=" + reason);
    }

    public String suggestOptimizations() {
        StringBuilder sb = new StringBuilder();

        if (lastTag != null) {
            sb.append("📊 آخر وسم: ").append(lastTag.vendor);
            sb.append(" | RSSI: ").append(lastTag.rssi).append(" dBm\n");
        }

        sb.append("\n⚙️ الإعدادات المقترحة:\n");
        sb.append("  • Frequency: ").append(readerCaps.getFrequencyRangeText()).append("\n");
        sb.append("  • Power: ").append(getOptimalPower()).append(" dBm\n");
        sb.append("  • Session: S").append(getOptimalSession()).append("\n");
        sb.append("  • Q Value: ").append(getOptimalQValue()).append("\n");

        return sb.toString();
    }

    // ==================== Persistence ====================

    private void persistLearnedSettings(String key, String type, int count) {
        prefs.edit()
                .putInt(type + "_" + key, count)
                .apply();
    }

    private void loadLearnedSettings() {
        Log.d(TAG, "📚 Learned settings loaded from " + PREFS);
    }

    public void resetLearning() {
        successCount.clear();
        failureCount.clear();
        lastTag = null;
        prefs.edit().clear().apply();
        Log.i(TAG, "🔄 Learning reset");
    }

    // ==================== Getters ====================

    public ReaderCapabilities getReaderCapabilities() { return readerCaps; }
    public TagProfiler getLastTag() { return lastTag; }
}
