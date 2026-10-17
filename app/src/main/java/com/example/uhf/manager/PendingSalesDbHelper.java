package com.example.uhf.manager;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PendingSalesDbHelper — SQLite Helper لطابور المبيعات Offline
 * ═══════════════════════════════════════════════════════════════════
 * 
 * @since v1.0
 */
public class PendingSalesDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "PendingSalesDb";
    private static final String DB_NAME = "mira_pending_sales.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE = "pending_sales";

    // الأعمدة
    public static final String COL_ID              = "id";
    public static final String COL_LOCAL_UUID      = "local_uuid";
    public static final String COL_PAYLOAD_JSON    = "payload_json";
    public static final String COL_BRANCH_ID       = "branch_id";
    public static final String COL_USER_ID         = "user_id";
    public static final String COL_TOTAL_USD       = "total_usd";
    public static final String COL_ITEM_COUNT      = "item_count";
    public static final String COL_CUSTOMER_NAME   = "customer_name";
    public static final String COL_CREATED_AT      = "created_at";
    public static final String COL_RETRY_COUNT     = "retry_count";
    public static final String COL_LAST_ATTEMPT_AT = "last_attempt_at";
    public static final String COL_LAST_ERROR      = "last_error";
    public static final String COL_STATUS          = "status";

    public PendingSalesDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createSql = "CREATE TABLE " + TABLE + " (" +
                COL_ID              + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_LOCAL_UUID      + " TEXT NOT NULL UNIQUE, " +
                COL_PAYLOAD_JSON    + " TEXT NOT NULL, " +
                COL_BRANCH_ID       + " INTEGER DEFAULT 0, " +
                COL_USER_ID         + " INTEGER DEFAULT 0, " +
                COL_TOTAL_USD       + " REAL DEFAULT 0, " +
                COL_ITEM_COUNT      + " INTEGER DEFAULT 0, " +
                COL_CUSTOMER_NAME   + " TEXT DEFAULT '', " +
                COL_CREATED_AT      + " INTEGER NOT NULL, " +
                COL_RETRY_COUNT     + " INTEGER DEFAULT 0, " +
                COL_LAST_ATTEMPT_AT + " INTEGER DEFAULT 0, " +
                COL_LAST_ERROR      + " TEXT DEFAULT '', " +
                COL_STATUS          + " INTEGER DEFAULT 0" +
                ")";

        db.execSQL(createSql);

        db.execSQL("CREATE INDEX idx_" + TABLE + "_status ON " 
                + TABLE + "(" + COL_STATUS + ")");
        db.execSQL("CREATE INDEX idx_" + TABLE + "_created ON " 
                + TABLE + "(" + COL_CREATED_AT + ")");

        Log.d(TAG, "✅ Database created: " + DB_NAME);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "⚠️ Upgrade " + oldVersion + " → " + newVersion 
                + " (dropping table)");
        
        // ⚠️ حذف الجدول القديم — لا نفقد البيانات المهمة لأنها مؤقتة
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }
}
