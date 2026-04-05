package com.kousoyu.drift.data

// ─── Domain Models ────────────────────────────────────────────────────────────

/**
 * A lightweight representation of a manga item used throughout the app.
 * Deliberately simple — only what the UI needs to render a card.
 */
data class Manga(
    val title: String,
    val coverUrl: String,
    val detailUrl: String,        // full URL to the manga detail page
    val latestChapter: String = "",
    val genre: String = "",
    val author: String = "",
    val sourceName: String
)

data class MangaChapter(
    val name: String,
    val url: String
)

/** Full metadata and chapter list for a single manga. */
data class MangaDetail(
    val url: String,
    val title: String,
    val coverUrl: String,
    val author: String,
    val description: String,
    val status: String,
    val chapters: List<MangaChapter>
)

// ─── Source Interface ─────────────────────────────────────────────────────────

/**
 * The contract that every manga source MUST implement.
 * The UI layer only knows about this interface — never about specific scrapers.
 */
interface MangaSource {
    val name: String
    val baseUrl: String

    /** Fetch the homepage's popular/hot manga list. */
    suspend fun getPopularManga(): Result<List<Manga>>

    /** Parse a search results page for the given query. */
    suspend fun searchManga(query: String): Result<List<Manga>>
    
    /** Fetch deep detail mapping (info + chapter list). */
    suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail>
    
    /** Extract the raw image URLs for a specific chapter page. */
    suspend fun getChapterImages(chapterUrl: String): Result<List<String>>
}
