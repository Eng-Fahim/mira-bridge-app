package com.example.uhf.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
    entities = {InventoryTransactionEntity.class}, 
    version = 1,
    exportSchema = false  // ← أضف هذا السطر
)
public abstract class InventoryDatabase extends RoomDatabase {
    public abstract InventoryTransactionDao inventoryTransactionDao();
}
