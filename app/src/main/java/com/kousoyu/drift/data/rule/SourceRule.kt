package com.kousoyu.drift.data.rule

/**
 * The root configuration for a dynamic manga source JSON rule.
 */
data class DynamicSourceConfig(
    val name: String,
    val baseUrl: String,
    val isR18: Boolean = false,
    val searchRule: SearchRule,
    val detailRule: DetailRule,
    val chapterImagesRule: ChapterImagesRule
)

/**
 * Rule for parsing the popular/search results page.
 */
data class SearchRule(
    val urlFormat: String,         // e.g. "/search?q={query}"
    val popularFormat: String,     // e.g. "/rank"
    val listSelector: String,      // CSS selector to get the list items
    val titleSelector: String,     // CSS selector + extractor e.g. "h3.title@text"
    val coverSelector: String,     // e.g. "img.lazy@data-src"
    val urlSelector: String,       // e.g. "a.poster@href"
    val detailUrlPrefix: String? = null, // Prepended to extracted URL, e.g. "/comic/"
    val detailUrlSuffix: String? = null, // Appended to extracted URL, e.g. "/"
    val latestChapterSelector: String? = null,
    val genreSelector: String? = null,
    val authorSelector: String? = null
)

/**
 * Rule for parsing the manga detail page.
 */
data class DetailRule(
    val titleSelector: String,
    val coverSelector: String,
    val authorSelector: String,
    val descSelector: String,
    val statusSelector: String,
    // Chapters list inside the detail page
    val chapterListSelector: String,
    val chapterNameSelector: String, // from context of the chapterList item
    val chapterUrlSelector: String
)

/**
 * Rule for extracting raw image URLs from a chapter reader page.
 */
data class ChapterImagesRule(
    val imageListSelector: String,
    val imageUrlSelector: String
)
