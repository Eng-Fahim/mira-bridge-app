package com.example.uhf.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface InventoryTransactionDao {
    @Insert
    long insertTransaction(InventoryTransactionEntity transaction);
    
    @Query("SELECT * FROM inventory_transactions WHERE synced = 0")
    List<InventoryTransactionEntity> getUnsyncedTransactions();
    
    @Query("UPDATE inventory_transactions SET synced = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Integer> ids);
    
    @Query("DELETE FROM inventory_transactions WHERE synced = 1 AND scan_timestamp < :cutoffTime")
    void cleanupSyncedTransactions(long cutoffTime);
}
