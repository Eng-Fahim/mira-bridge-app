package com.example.uhf.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ MiraFileLogger — نظام تسجيل داخلي في ملف
 * 
 * الاستخدام:
 *   MiraFileLogger.init(context);
 *   MiraFileLogger.log("ChainwayReader", "⚡ Power applied: 30 dBm");
 *   MiraFileLogger.logError("ChainwayReader", "❌ Init failed", exception);
 * 
 * الموقع:
 *   /sdcard/Android/data/<package>/files/logs/mira_YYYY-MM-DD.log
 * 
 * يمكن الوصول للملف عبر:
 *   - مدير ملفات (Files by Google)
 *   - Android Studio Device File Explorer
 *   - adb pull
 */
public class MiraFileLogger {

    private static final String TAG = "MiraFileLogger";
    private static final String LOG_DIR = "logs";
    private static final String LOG_PREFIX = "mira_";
    private static final String LOG_SUFFIX = ".log";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;  // 5 MB
    private static final int MAX_FILES_TO_KEEP = 5;

    private static volatile MiraFileLogger instance;
    private static volatile boolean enabled = true;

    private final Context context;
    private final File logDir;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat timestampFormat;

    private File currentLogFile;
    private BufferedWriter writer;
    private long currentFileSize = 0;

    // ==================== Singleton ====================

    private MiraFileLogger(Context context) {
        this.context = context.getApplicationContext();
        this.logDir = new File(this.context.getExternalFilesDir(null), LOG_DIR);

        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        this.timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

        initializeLogDirectory();
        openTodayLogFile();
        cleanupOldLogs();
    }

    public static void init(Context context) {
        if (instance == null) {
            synchronized (MiraFileLogger.class) {
                if (instance == null) {
                    try {
                        instance = new MiraFileLogger(context);
                        Log.i(TAG, "✅ MiraFileLogger initialized: " + instance.getLogDirPath());
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to initialize MiraFileLogger", e);
                        enabled = false;
                    }
                }
            }
        }
    }

    public static MiraFileLogger getInstance() {
        return instance;
    }

    public static boolean isEnabled() {
        return enabled && instance != null;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    // ==================== Public API ====================

    public static void log(String tag, String message) {
        write("INFO", tag, message, null);
    }

    public static void logDebug(String tag, String message) {
        write("DEBUG", tag, message, null);
    }

    public static void logWarn(String tag, String message) {
        write("WARN", tag, message, null);
    }

    public static void logError(String tag, String message) {
        write("ERROR", tag, message, null);
    }

    public static void logError(String tag, String message, Throwable throwable) {
        write("ERROR", tag, message, throwable);
    }

    // ==================== Core ====================

    private static void write(String level, String tag, String message, Throwable throwable) {
        // ✅ اكتب في Logcat دائماً (للتشخيص اليدوي إن وُجد)
        int priority = levelToPriority(level);
        Log.println(priority, tag, message);

        if (throwable != null) {
            Log.println(Log.ERROR, tag, Log.getStackTraceString(throwable));
        }

        // ✅ اكتب في الملف (إذا كان النظام مُهيّأ)
        if (!isEnabled()) return;

        try {
            instance.writeToFile(level, tag, message, throwable);
        } catch (Exception e) {
            // لا نُعطّل النظام إذا فشلت كتابة واحدة
            Log.e(TAG, "Failed to write log", e);
        }
    }

    private synchronized void writeToFile(String level, String tag,
                                           String message, Throwable throwable) {
        try {
            if (writer == null) {
                openTodayLogFile();
            }

            String timestamp = timestampFormat.format(new Date());
            String line = String.format(Locale.US,
                    "%s | %-5s | %-25s | %s",
                    timestamp, level, tag, message);

            writer.write(line);
            writer.newLine();

            if (throwable != null) {
                writer.write("    → " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
                writer.newLine();

                StackTraceElement[] stack = throwable.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 5); i++) {
                    writer.write("       at " + stack[i].toString());
                    writer.newLine();
                }
            }

            writer.flush();
            currentFileSize += line.length() + 1;

            // ✅ إذا كبر الملف، أنشئ ملفاً جديداً
            if (currentFileSize > MAX_FILE_SIZE) {
                rotateLogFile();
            }

        } catch (IOException e) {
            Log.e(TAG, "writeToFile error", e);
        }
    }

    // ==================== File Management ====================

    private void initializeLogDirectory() {
        if (!logDir.exists()) {
            boolean created = logDir.mkdirs();
            if (!created) {
                Log.e(TAG, "❌ Failed to create log directory: " + logDir.getAbsolutePath());
                enabled = false;
                return;
            }
        }
        if (!logDir.canWrite()) {
            Log.e(TAG, "❌ Log directory not writable: " + logDir.getAbsolutePath());
            enabled = false;
        }
    }

    private void openTodayLogFile() {
        try {
            closeWriter();

            String fileName = LOG_PREFIX + dateFormat.format(new Date()) + LOG_SUFFIX;
            currentLogFile = new File(logDir, fileName);

            writer = new BufferedWriter(new FileWriter(currentLogFile, true));
            currentFileSize = currentLogFile.length();

            writeToFile("INFO", "MiraFileLogger",
                    "═══════════ Session started ═══════════", null);

        } catch (IOException e) {
            Log.e(TAG, "openTodayLogFile error", e);
            enabled = false;
        }
    }

    private void rotateLogFile() {
        try {
            closeWriter();

            String timestamp = new SimpleDateFormat("HHmmss", Locale.US)
                    .format(new Date());
            String fileName = LOG_PREFIX + dateFormat.format(new Date())
                    + "_" + timestamp + LOG_SUFFIX;
            currentLogFile = new File(logDir, fileName);

            writer = new BufferedWriter(new FileWriter(currentLogFile, true));
            currentFileSize = 0;

            writeToFile("INFO", "MiraFileLogger", "───── Log rotated ─────", null);
        } catch (IOException e) {
            Log.e(TAG, "rotateLogFile error", e);
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
    }

    private void cleanupOldLogs() {
        try {
            File[] files = logDir.listFiles((dir, name) ->
                    name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX));

            if (files == null || files.length <= MAX_FILES_TO_KEEP) return;

            // ✅ رتّب حسب التاريخ (الأحدث أولاً)
            java.util.Arrays.sort(files, (a, b) ->
                    Long.compare(b.lastModified(), a.lastModified()));

            // ✅ احذف الأقدم
            for (int i = MAX_FILES_TO_KEEP; i < files.length; i++) {
                if (files[i].delete()) {
                    Log.d(TAG, "Deleted old log: " + files[i].getName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanupOldLogs error", e);
        }
    }

    // ==================== Public Helpers ====================

    /**
     * ✅ الحصول على مسار مجلد الـ logs
     */
    public String getLogDirPath() {
        return logDir != null ? logDir.getAbsolutePath() : "N/A";
    }

    /**
     * ✅ الحصول على ملف اللوج الحالي
     */
    public File getCurrentLogFile() {
        return currentLogFile;
    }

    /**
     * ✅ قراءة كل محتوى اللوج الحالي
     */
    public String readCurrentLog() {
        try {
            if (currentLogFile == null || !currentLogFile.exists()) {
                return "No log file";
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(currentLogFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "Error reading log: " + e.getMessage();
        }
    }

    /**
     * ✅ حذف كل ملفات اللوج
     */
    public void clearAllLogs() {
        try {
            closeWriter();
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            openTodayLogFile();
        } catch (Exception e) {
            Log.e(TAG, "clearAllLogs error", e);
        }
    }

    /**
     * ✅ إغلاق نهائي (عند إغلاق التطبيق)
     */
    public synchronized void shutdown() {
        writeToFile("INFO", "MiraFileLogger",
                "═══════════ Session ended ═══════════", null);
        closeWriter();
    }

    // ==================== Utils ====================

    private static int levelToPriority(String level) {
        switch (level) {
            case "DEBUG": return Log.DEBUG;
            case "WARN":  return Log.WARN;
            case "ERROR": return Log.ERROR;
            default:      return Log.INFO;
        }
    }
}
