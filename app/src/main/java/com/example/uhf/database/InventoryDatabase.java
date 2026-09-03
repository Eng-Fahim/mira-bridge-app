package com.example.uhf.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
    entities = {InventoryTransactionEntity.class}, 
    version = 1,
    exportSchema = false
)
public abstract class InventoryDatabase extends RoomDatabase {

    private static volatile InventoryDatabase INSTANCE;

    public abstract InventoryTransactionDao inventoryTransactionDao();

    public static InventoryDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (InventoryDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            InventoryDatabase.class,
                            "inventory_database"
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
