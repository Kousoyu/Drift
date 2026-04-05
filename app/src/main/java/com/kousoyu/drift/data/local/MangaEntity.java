package com.kousoyu.drift.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "manga_bookshelf")
public class MangaEntity {
    @PrimaryKey
    @NonNull
    public String detailUrl;
    
    @NonNull
    public String title;
    
    @NonNull
    public String coverUrl;
    
    @NonNull
    public String sourceName = "包子漫画";
    
    @NonNull
    public String latestChapter = "";
    
    @NonNull
    public String genre = "";
    
    public String lastReadChapterName = null;
    public String lastReadChapterUrl = null;
    
    public long addedAt = System.currentTimeMillis();

    public MangaEntity(@NonNull String detailUrl, @NonNull String title, @NonNull String coverUrl, 
                       @NonNull String sourceName, @NonNull String latestChapter, @NonNull String genre) {
        this.detailUrl = detailUrl;
        this.title = title;
        this.coverUrl = coverUrl;
        this.sourceName = sourceName;
        this.latestChapter = latestChapter;
        this.genre = genre;
    }
}
