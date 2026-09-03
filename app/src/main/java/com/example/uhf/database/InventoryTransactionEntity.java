package com.example.uhf.database;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "inventory_transactions")
public class InventoryTransactionEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "timestamp")
    public long timestamp;
    
    @ColumnInfo(name = "session_id")
    public String sessionId;
    
    @ColumnInfo(name = "epc_code")
    public String epcCode;
    
    @ColumnInfo(name = "rssi")
    public int rssi;
    
    @ColumnInfo(name = "scan_timestamp")
    public long scanTimestamp; // تم تعديل اسم المتغير ليطابق الاستعلام تماماً
    
    @ColumnInfo(name = "device_id")
    public String deviceId;
    
    @ColumnInfo(name = "user_id")
    public String userId;
    
    @ColumnInfo(name = "synced")
    public boolean synced;
    
    @ColumnInfo(name = "branch_id")
    public int branchId;
}
