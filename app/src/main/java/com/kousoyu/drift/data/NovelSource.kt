package com.kousoyu.drift.data

// ─── Novel Domain Models ─────────────────────────────────────────────────────

/**
 * A lightweight representation of a novel item for list display.
 * Parallel to [Manga] — only what the UI needs to render a card.
 */
data class NovelItem(
    val title: String,
    val author: String,
    val coverUrl: String,
    val detailUrl: String,       // full URL to the novel detail page
    val genre: String = "",
    val status: String = "",     // 连载 / 完结
    val sourceName: String
)

/**
 * A chapter within a volume.
 */
data class NovelChapter(
    val name: String,
    val url: String
)

/**
 * A volume (卷/册) containing chapters.
 * Light novels are organized as volumes, unlike web novels which are flat.
 */
data class NovelVolume(
    val name: String,
    val chapters: List<NovelChapter>
)

/**
 * Full metadata and volume/chapter structure for a single novel.
 */
data class NovelDetail(
    val url: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val description: String,
    val status: String,
    val genre: String = "",
    val volumes: List<NovelVolume>
)

// ─── Novel Source Interface ──────────────────────────────────────────────────

/**
 * The contract that every novel source MUST implement.
 * Parallel to [MangaSource] — the UI layer only knows this interface.
 *
 * Key difference from MangaSource:
 * - [getChapterContent] returns plain text (String), not image URLs
 * - [NovelDetail] has a volume→chapter hierarchy
 */
interface NovelSource {
    val name: String
    val baseUrl: String

    /** Fetch the homepage's popular/trending novels. */
    suspend fun getPopularNovels(): Result<List<NovelItem>>

    /** Search novels by query. */
    suspend fun searchNovel(query: String): Result<List<NovelItem>>

    /** Fetch full detail + volume/chapter structure. */
    suspend fun getNovelDetail(detailUrl: String): Result<NovelDetail>

    /** Extract the chapter text content as plain text / simple HTML. */
    suspend fun getChapterContent(chapterUrl: String): Result<String>

    /** HTTP headers for requests (e.g. Referer). */
    fun getHeaders(): Map<String, String> = emptyMap()
}
