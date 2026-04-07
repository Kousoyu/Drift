package com.kousoyu.drift.data.local;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import kotlinx.coroutines.flow.Flow;
import java.util.List;

@Dao
public interface MangaDao {
    @Query("SELECT * FROM manga_bookshelf ORDER BY addedAt DESC")
    Flow<List<MangaEntity>> getAllFavorites();

    @Query("SELECT * FROM manga_bookshelf WHERE detailUrl = :url LIMIT 1")
    MangaEntity getMangaByUrlSync(String url);

    @Query("SELECT * FROM manga_bookshelf WHERE detailUrl = :url LIMIT 1")
    Flow<MangaEntity> getMangaByUrlFlow(String url);

    @Query("SELECT EXISTS(SELECT * FROM manga_bookshelf WHERE detailUrl = :url)")
    Flow<Boolean> isFavorite(String url);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMangaSync(MangaEntity manga);

    @Query("UPDATE manga_bookshelf SET lastReadChapterName = :chapterName, lastReadChapterUrl = :chapterUrl WHERE detailUrl = :mangaUrl")
    void updateReadingProgressSync(String mangaUrl, String chapterName, String chapterUrl);

    @Query("UPDATE manga_bookshelf SET lastReadPage = :page WHERE detailUrl = :mangaUrl")
    void updateReadingPageSync(String mangaUrl, int page);

    @Query("UPDATE manga_bookshelf SET totalChapters = :count, latestChapter = :latestName WHERE detailUrl = :mangaUrl")
    void updateChapterCountSync(String mangaUrl, int count, String latestName);

    @Delete
    void deleteMangaSync(MangaEntity manga);

    @Query("SELECT COUNT(*) FROM manga_bookshelf")
    Flow<Integer> getFavoriteCount();
}
