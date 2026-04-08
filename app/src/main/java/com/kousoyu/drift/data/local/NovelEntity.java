package com.kousoyu.drift.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "novel_bookshelf")
public class NovelEntity {
    @PrimaryKey
    @NonNull
    public String detailUrl;

    @NonNull
    public String title;

    @NonNull
    public String coverUrl;

    @NonNull
    public String sourceName = "哔哩轻小说";

    @NonNull
    public String author = "";

    public String lastReadChapterName = null;
    public String lastReadChapterUrl = null;
    public int totalChapters = 0;
    public long lastReadAt = 0;

    public long addedAt = System.currentTimeMillis();

    public NovelEntity(@NonNull String detailUrl, @NonNull String title, @NonNull String coverUrl,
                       @NonNull String sourceName, @NonNull String author) {
        this.detailUrl = detailUrl;
        this.title = title;
        this.coverUrl = coverUrl;
        this.sourceName = sourceName;
        this.author = author;
    }
}
