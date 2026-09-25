package com.example.uhf.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * DiagTracer v1.0
 * ═══════════════════════════════════════════════════════════════
 * نظام تشخيص ذاتي — يعرض المراحل على الشاشة (Toast) بدلاً من Logcat.
 *
 * الاستخدام:
 *   DiagTracer.init(context);           // في onCreate للنشاط
 *   DiagTracer.mark("onCreate start");  // عند بداية مرحلة
 *   DiagTracer.mark("after bindViews"); // بعد كل خطوة
 *
 * كل mark يُظهر Toast سريع على الشاشة + يُسجَّل في الذاكرة.
 * عند حدوث خطأ، DiagTracer.dump() يعرض كل المراحل في AlertDialog.
 */
public final class DiagTracer {

    private static final String TAG = "DiagTracer";
    private static final boolean ENABLED = false;   // ✅ فعّله للتشخيص

    private static Context appContext;
    private static final long START_TIME = SystemClock.uptimeMillis();
    private static final List<String> marks = new ArrayList<>();
    private static boolean lastMarkCrashed = false;

    private DiagTracer() {}

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    /**
     * يسجّل مرحلة + يعرضها على الشاشة كـ Toast قصير.
     */
    public static void mark(String label) {
        if (!ENABLED) return;

        long elapsed = SystemClock.uptimeMillis() - START_TIME;
        String msg = "[" + elapsed + "ms] " + label;

        synchronized (marks) {
            marks.add(msg);
            if (marks.size() > 200) {
                marks.remove(0);   // نُبقي آخر 200 mark
            }
        }

        // ✅ اطبع في Logcat أيضاً (لو متوفر)
        Log.e(TAG, msg);

        // ✅ اعرض Toast على UI thread
        if (appContext != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * يسجّل مرحلة مع تفاصيل إضافية (مثل قيم متغيرات).
     */
    public static void mark(String label, String details) {
        mark(label + " → " + details);
    }

    /**
     * يسجّل استثناءً.
     */
    public static void markException(String label, Throwable t) {
        if (!ENABLED) return;

        String msg;
        if (t == null) {
            msg = "[EX] " + label + " → (null)";
        } else {
            msg = "[EX] " + label + " → " + t.getClass().getSimpleName() 
                    + ": " + t.getMessage();
        }

        synchronized (marks) {
            marks.add(msg);
        }

        Log.e(TAG, msg, t);
        lastMarkCrashed = true;

        if (appContext != null) {
            final String finalMsg = msg;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(appContext, "❌ " + finalMsg, 
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * يحفظ كل المراحل في سلسلة نصية (للعرض).
     */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════ DiagTracer Dump ══════\n");
        synchronized (marks) {
            for (String m : marks) {
                sb.append(m).append("\n");
            }
        }
        sb.append("══════════════════════════════");
        return sb.toString();
    }

    /**
     * يُرجع عدد المراحل المسجّلة.
     */
    public static int getMarkCount() {
        synchronized (marks) {
            return marks.size();
        }
    }

    /**
     * يُرجع آخر مرحلة (للمعرفة أين توقف التنفيذ).
     */
    public static String getLastMark() {
        synchronized (marks) {
            if (marks.isEmpty()) return "(none)";
            return marks.get(marks.size() - 1);
        }
    }

    /**
     * يُصفّر السجل.
     */
    public static void reset() {
        synchronized (marks) {
            marks.clear();
            lastMarkCrashed = false;
        }
    }
}
