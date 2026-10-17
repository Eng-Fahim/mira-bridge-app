package com.example.uhf.manager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PendingSalesQueue — مدير طابور المبيعات Offline
 * ═══════════════════════════════════════════════════════════════════
 * 
 * Singleton — يُستخدم من MiraPosFragment و OfflineSalesSyncer.
 * 
 * @since v1.0
 */
public class PendingSalesQueue {

    private static final String TAG = "PendingSalesQueue";

    /** الحد الأقصى لعدد مرات إعادة المحاولة قبل وسم البيع كـ FAILED */
    private static final int MAX_RETRIES = 5;

    /** عدد الأيام للاحتفاظ بالبيوع المُرسلة قبل حذفها */
    private static final int SENT_RETENTION_DAYS = 7;

    private static volatile PendingSalesQueue instance;
    private static final Object LOCK = new Object();

    private final PendingSalesDbHelper helper;

    private PendingSalesQueue(Context context) {
        this.helper = new PendingSalesDbHelper(context.getApplicationContext());
    }

    public static PendingSalesQueue getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new PendingSalesQueue(context);
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    //  ➕ إضافة بيع للطابور
    // ═══════════════════════════════════════════════════════════════

    /**
     * ✅ يحفظ بيعاً فاشلاً للطابور.
     *    لو نفس UUID موجود → يحدّثه بدل التكرار.
     */
    public boolean enqueue(PendingSale sale) {
        if (sale == null 
                || sale.localUuid == null || sale.localUuid.isEmpty()
                || sale.payloadJson == null || sale.payloadJson.isEmpty()) {
            Log.w(TAG, "enqueue: invalid sale — skipped");
            return false;
        }

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(PendingSalesDbHelper.COL_LOCAL_UUID, sale.localUuid);
            cv.put(PendingSalesDbHelper.COL_PAYLOAD_JSON, sale.payloadJson);
            cv.put(PendingSalesDbHelper.COL_BRANCH_ID, sale.branchId);
            cv.put(PendingSalesDbHelper.COL_USER_ID, sale.userId);
            cv.put(PendingSalesDbHelper.COL_TOTAL_USD, sale.totalUsd);
            cv.put(PendingSalesDbHelper.COL_ITEM_COUNT, sale.itemCount);
            cv.put(PendingSalesDbHelper.COL_CUSTOMER_NAME, 
                    sale.customerName != null ? sale.customerName : "");
            cv.put(PendingSalesDbHelper.COL_CREATED_AT, 
                    sale.createdAt > 0 ? sale.createdAt : System.currentTimeMillis());
            cv.put(PendingSalesDbHelper.COL_RETRY_COUNT, 0);
            cv.put(PendingSalesDbHelper.COL_LAST_ATTEMPT_AT, 0);
            cv.put(PendingSalesDbHelper.COL_LAST_ERROR, "");
            cv.put(PendingSalesDbHelper.COL_STATUS, PendingSale.STATUS_PENDING);

            long result = db.insertWithOnConflict(
                    PendingSalesDbHelper.TABLE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);

            if (result != -1) {
                Log.i(TAG, "✅ Enqueued: " + sale.localUuid 
                        + " | total=" + sale.totalUsd
                        + " | items=" + sale.itemCount);
                return true;
            } else {
                Log.e(TAG, "❌ Failed to enqueue: " + sale.localUuid);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "enqueue error", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  📋 جلب القائمة
    // ═══════════════════════════════════════════════════════════════

    /** جميع البيوع في حالة PENDING (الأقدم أولاً) */
    public List<PendingSale> getPending(int limit) {
        return query("status = ?", 
                new String[]{ String.valueOf(PendingSale.STATUS_PENDING) },
                "created_at ASC", limit);
    }

    public List<PendingSale> getPending() {
        return getPending(0);
    }

    /** جميع البيوع (أي حالة) */
    public List<PendingSale> getAll(int limit) {
        return query(null, null, "created_at DESC", limit);
    }

    public List<PendingSale> getAll() {
        return getAll(0);
    }

    public PendingSale getByUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        List<PendingSale> list = query("local_uuid = ?", 
                new String[]{ uuid }, null, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔢 العد
    // ═══════════════════════════════════════════════════════════════

    public int countPending() {
        return count("status = ?", 
                new String[]{ String.valueOf(PendingSale.STATUS_PENDING) });
    }

    public int countFailed() {
        return count("status = ?", 
                new String[]{ String.valueOf(PendingSale.STATUS_FAILED) });
    }

    public int countAll() {
        return count(null, null);
    }

    public boolean hasPending() {
        return countPending() > 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔄 تحديث الحالة
    // ═══════════════════════════════════════════════════════════════

    public boolean markAsSent(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(PendingSalesDbHelper.COL_STATUS, PendingSale.STATUS_SENT);
            cv.put(PendingSalesDbHelper.COL_LAST_ATTEMPT_AT, 
                    System.currentTimeMillis());
            cv.put(PendingSalesDbHelper.COL_LAST_ERROR, "");

            int rows = db.update(PendingSalesDbHelper.TABLE, cv,
                    "local_uuid = ?", new String[]{ uuid });

            if (rows > 0) {
                Log.i(TAG, "✅ Marked as SENT: " + uuid);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "markAsSent error", e);
            return false;
        }
    }

    public boolean markAsFailed(String uuid, String error) {
        if (uuid == null || uuid.isEmpty()) return false;

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(PendingSalesDbHelper.COL_STATUS, PendingSale.STATUS_FAILED);
            cv.put(PendingSalesDbHelper.COL_LAST_ATTEMPT_AT, 
                    System.currentTimeMillis());
            cv.put(PendingSalesDbHelper.COL_LAST_ERROR, 
                    error != null ? error : "");

            int rows = db.update(PendingSalesDbHelper.TABLE, cv,
                    "local_uuid = ?", new String[]{ uuid });

            if (rows > 0) {
                Log.w(TAG, "⚠️ Marked as FAILED: " + uuid + " | " + error);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "markAsFailed error", e);
            return false;
        }
    }

    /**
     * ✅ يزيد عدد المحاولات.
     *    لو تجاوز MAX_RETRIES → يوسمه FAILED.
     */
    public boolean incrementRetry(String uuid, String lastError) {
        if (uuid == null || uuid.isEmpty()) return false;

        PendingSale sale = getByUuid(uuid);
        if (sale == null) return false;

        int newCount = sale.retryCount + 1;
        boolean shouldFail = newCount >= MAX_RETRIES;

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(PendingSalesDbHelper.COL_RETRY_COUNT, newCount);
            cv.put(PendingSalesDbHelper.COL_LAST_ATTEMPT_AT, 
                    System.currentTimeMillis());
            cv.put(PendingSalesDbHelper.COL_LAST_ERROR, 
                    lastError != null ? lastError : "");
            cv.put(PendingSalesDbHelper.COL_STATUS, 
                    shouldFail ? PendingSale.STATUS_FAILED 
                               : PendingSale.STATUS_PENDING);

            int rows = db.update(PendingSalesDbHelper.TABLE, cv,
                    "local_uuid = ?", new String[]{ uuid });

            if (rows > 0) {
                Log.d(TAG, "Retry " + newCount + "/" + MAX_RETRIES 
                        + " for " + uuid 
                        + (shouldFail ? " → FAILED" : ""));
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "incrementRetry error", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🗑️ حذف
    // ═══════════════════════════════════════════════════════════════

    public boolean delete(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            int rows = db.delete(PendingSalesDbHelper.TABLE,
                    "local_uuid = ?", new String[]{ uuid });
            return rows > 0;
        } catch (Exception e) {
            Log.e(TAG, "delete error", e);
            return false;
        }
    }

    public int deleteAllSent() {
        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            return db.delete(PendingSalesDbHelper.TABLE,
                    "status = ?", 
                    new String[]{ String.valueOf(PendingSale.STATUS_SENT) });
        } catch (Exception e) {
            Log.e(TAG, "deleteAllSent error", e);
            return 0;
        }
    }

    /**
     * ✅ يحذف البيوع المُرسلة الأقدم من X أيام.
     *    يُستدعى دورياً لتنظيف الـ DB.
     */
    public int cleanupOldSent() {
        long cutoff = System.currentTimeMillis() 
                - (SENT_RETENTION_DAYS * 24L * 60L * 60L * 1000L);

        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            int rows = db.delete(PendingSalesDbHelper.TABLE,
                    "status = ? AND last_attempt_at < ?",
                    new String[]{
                            String.valueOf(PendingSale.STATUS_SENT),
                            String.valueOf(cutoff)
                    });
            if (rows > 0) {
                Log.i(TAG, "🧹 Cleaned up " + rows + " old sent sales");
            }
            return rows;
        } catch (Exception e) {
            Log.e(TAG, "cleanupOldSent error", e);
            return 0;
        }
    }

    public int deleteAll() {
        SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            return db.delete(PendingSalesDbHelper.TABLE, null, null);
        } catch (Exception e) {
            Log.e(TAG, "deleteAll error", e);
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔧 Query Internals
    // ═══════════════════════════════════════════════════════════════

    private List<PendingSale> query(String where, String[] args,
                                    String orderBy, int limit) {
        List<PendingSale> result = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;

        try {
            db = helper.getReadableDatabase();

            String limitClause = (limit > 0) ? String.valueOf(limit) : null;

            cursor = db.query(
                    PendingSalesDbHelper.TABLE,
                    null,       // all columns
                    where,
                    args,
                    null,       // groupBy
                    null,       // having
                    orderBy,
                    limitClause
            );

            while (cursor != null && cursor.moveToNext()) {
                result.add(cursorToSale(cursor));
            }
        } catch (Exception e) {
            Log.e(TAG, "query error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    private int count(String where, String[] args) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = helper.getReadableDatabase();
            String sql = "SELECT COUNT(*) FROM " + PendingSalesDbHelper.TABLE;
            if (where != null && !where.isEmpty()) {
                sql += " WHERE " + where;
            }
            cursor = db.rawQuery(sql, args);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "count error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    private PendingSale cursorToSale(Cursor c) {
        PendingSale s = new PendingSale();
        s.id = c.getLong(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_ID));
        s.localUuid = c.getString(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_LOCAL_UUID));
        s.payloadJson = c.getString(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_PAYLOAD_JSON));
        s.branchId = c.getInt(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_BRANCH_ID));
        s.userId = c.getInt(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_USER_ID));
        s.totalUsd = c.getDouble(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_TOTAL_USD));
        s.itemCount = c.getInt(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_ITEM_COUNT));
        s.customerName = c.getString(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_CUSTOMER_NAME));
        s.createdAt = c.getLong(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_CREATED_AT));
        s.retryCount = c.getInt(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_RETRY_COUNT));
        s.lastAttemptAt = c.getLong(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_LAST_ATTEMPT_AT));
        s.lastError = c.getString(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_LAST_ERROR));
        s.status = c.getInt(c.getColumnIndexOrThrow(PendingSalesDbHelper.COL_STATUS));
        return s;
    }
}
