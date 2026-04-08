package com.kousoyu.drift.data.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {MangaEntity.class, NovelEntity.class}, version = 3, exportSchema = false)
public abstract class DriftDatabase extends RoomDatabase {
    public abstract MangaDao mangaDao();
    public abstract NovelDao novelDao();
    
    private static volatile DriftDatabase INSTANCE;

    // Migration 2→3: add novel_bookshelf table (preserves manga data)
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `novel_bookshelf` ("
                + "`detailUrl` TEXT NOT NULL, "
                + "`title` TEXT NOT NULL, "
                + "`coverUrl` TEXT NOT NULL, "
                + "`sourceName` TEXT NOT NULL DEFAULT '哔哩轻小说', "
                + "`author` TEXT NOT NULL DEFAULT '', "
                + "`lastReadChapterName` TEXT, "
                + "`lastReadChapterUrl` TEXT, "
                + "`totalChapters` INTEGER NOT NULL DEFAULT 0, "
                + "`addedAt` INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(`detailUrl`))");
        }
    };
    
    public static DriftDatabase getDatabase(Context context) {
        if (INSTANCE == null) {
            synchronized (DriftDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            DriftDatabase.class, "drift_database")
                            .addMigrations(MIGRATION_2_3)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
