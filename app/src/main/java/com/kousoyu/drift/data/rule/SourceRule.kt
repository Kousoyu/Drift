package com.kousoyu.drift.data.rule

/**
 * The root configuration for a dynamic manga source JSON rule.
 *
 * All fields can be specified in the remote rules/sources.json hosted on GitHub.
 * When the JSON is updated, all users receive the new rules on next app launch —
 * zero APK republishing needed.
 *
 * Selector syntax supported by RuleEvaluator:
 *   - "css.selector@text"        → element text
 *   - "css.selector@href"        → attribute value
 *   - "css.selector@data-src"    → any attribute
 *   - "@regex:PATTERN"           → regex group 1 match on the element's outer HTML
 *   - "@json:path.to.field"      → JSON path traversal (for API sources)
 */
data class DynamicSourceConfig(
    val name: String,
    val baseUrl: String,
    /** Fallback domains tried in order if baseUrl is unreachable or returns error. */
    val mirrorUrls: List<String> = emptyList(),
    /** Extra HTTP headers sent with every request (e.g. Cookie, Referer overrides). */
    val headers: Map<String, String> = emptyMap(),
    /** Set to false in the remote JSON to silently disable this source for all users. */
    val enabled: Boolean = true,
    val isR18: Boolean = false,
    val searchRule: SearchRule,
    val detailRule: DetailRule,
    val chapterImagesRule: ChapterImagesRule
)

/**
 * Rule for parsing the popular/search results page.
 */
data class SearchRule(
    val urlFormat: String,              // e.g. "/search?q={query}"
    val popularFormat: String,          // e.g. "/rank"
    val listSelector: String,           // CSS selector to get the list items
    val titleSelector: String,          // e.g. "h3.title@text"
    val coverSelector: String,          // e.g. "img.lazy@data-src"
    val urlSelector: String,            // e.g. "a.poster@href"
    val detailUrlPrefix: String? = null,
    val detailUrlSuffix: String? = null,
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
    val chapterListSelector: String,
    val chapterNameSelector: String,
    val chapterUrlSelector: String,
    /** If chapters are on a separate endpoint, specify the URL template here.
     *  Use "{detailUrl}" as placeholder. Leave null to use the detail page itself. */
    val chapterListUrl: String? = null
)

/**
 * Rule for extracting raw image URLs from a chapter reader page.
 */
data class ChapterImagesRule(
    val imageListSelector: String,
    val imageUrlSelector: String,
    /** Base URL prepended to relative image paths (overrides source baseUrl). */
    val imageBaseUrl: String? = null
)

