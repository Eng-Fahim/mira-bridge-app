package com.example.uhf.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.uhf.data.entity.EncodedItemEntity;

import java.util.List;

@Dao
public interface EncodedItemDao {
    
    @Insert(onConflict = OnConflictStrategy.ABORT) // سيفشل إذا كان EPC مكرراً
    long insertItem(EncodedItemEntity item);
    
    @Insert(onConflict = OnConflictStrategy.IGNORE) // سيتجاهل إذا كان EPC مكرراً
    long insertItemIgnoreDuplicate(EncodedItemEntity item);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE) // سيستبدل إذا كان EPC مكرراً
    long insertItemReplace(EncodedItemEntity item);
    
    @Update
    void updateItem(EncodedItemEntity item);
    
    @Delete
    void deleteItem(EncodedItemEntity item);
    
    @Query("SELECT * FROM encoded_items WHERE epc = :epc LIMIT 1")
    EncodedItemEntity findByEpc(String epc);
    
    @Query("SELECT EXISTS(SELECT 1 FROM encoded_items WHERE epc = :epc)")
    boolean isEpcExists(String epc);
    
    @Query("SELECT * FROM encoded_items WHERE isSynced = 0")
    List<EncodedItemEntity> getUnsyncedItems();
    
    @Query("SELECT * FROM encoded_items ORDER BY createdAt DESC")
    List<EncodedItemEntity> getAllItems();
    
    @Query("UPDATE encoded_items SET isSynced = 1 WHERE epc = :epc")
    void markAsSynced(String epc);
    
    @Query("DELETE FROM encoded_items WHERE epc = :epc")
    void deleteByEpc(String epc);
}
