package com.example.uhf.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.uhf.data.dao.EncodedItemDao;
import com.example.uhf.data.entity.EncodedItemEntity;

@Database(
    entities = {EncodedItemEntity.class},
    version = 1,
    exportSchema = false
)
public abstract class MiraDatabase extends RoomDatabase {
    
    private static volatile MiraDatabase INSTANCE;
    
    public abstract EncodedItemDao encodedItemDao();
    
    public static MiraDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MiraDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        MiraDatabase.class,
                        "mira_database.db"
                    )
                    .fallbackToDestructiveMigration() // للتطوير فقط - يجب تغييره للإنتاج
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
