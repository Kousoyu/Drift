package com.kousoyu.drift.data.local;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import kotlinx.coroutines.flow.Flow;
import java.util.List;

@Dao
public interface NovelDao {
    // Sort: recently-read first, then by addedAt
    @Query("SELECT * FROM novel_bookshelf ORDER BY " +
           "CASE WHEN lastReadAt > 0 THEN lastReadAt ELSE addedAt END DESC")
    Flow<List<NovelEntity>> getAllFavorites();

    @Query("SELECT * FROM novel_bookshelf WHERE detailUrl = :url LIMIT 1")
    NovelEntity getNovelByUrlSync(String url);

    @Query("SELECT EXISTS(SELECT * FROM novel_bookshelf WHERE detailUrl = :url)")
    Flow<Boolean> isFavorite(String url);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNovelSync(NovelEntity novel);

    @Query("UPDATE novel_bookshelf SET lastReadChapterName = :chapterName, " +
           "lastReadChapterUrl = :chapterUrl, lastReadAt = :timestamp " +
           "WHERE detailUrl = :novelUrl")
    void updateReadingProgressSync(String novelUrl, String chapterName,
                                   String chapterUrl, long timestamp);

    @Query("UPDATE novel_bookshelf SET totalChapters = :count WHERE detailUrl = :novelUrl")
    void updateChapterCountSync(String novelUrl, int count);

    @Delete
    void deleteNovelSync(NovelEntity novel);

    @Query("SELECT COUNT(*) FROM novel_bookshelf")
    Flow<Integer> getFavoriteCount();
}
