package com.kousoyu.drift.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {MangaEntity.class}, version = 1, exportSchema = false)
public abstract class DriftDatabase extends RoomDatabase {
    public abstract MangaDao mangaDao();
    
    private static volatile DriftDatabase INSTANCE;
    
    public static DriftDatabase getDatabase(Context context) {
        if (INSTANCE == null) {
            synchronized (DriftDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            DriftDatabase.class, "drift_database")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
