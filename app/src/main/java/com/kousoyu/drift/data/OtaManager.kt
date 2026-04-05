package com.kousoyu.drift.data

import android.content.Context
import com.kousoyu.drift.data.rule.ChapterImagesRule
import com.kousoyu.drift.data.rule.DetailRule
import com.kousoyu.drift.data.rule.DynamicMangaSource
import com.kousoyu.drift.data.rule.DynamicSourceConfig
import com.kousoyu.drift.data.rule.SearchRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

object OtaManager {

    // ─── Remote rule endpoint ─────────────────────────────────────────────────
    // This raw GitHub URL is what's polled on every launch.
    // To update sources for ALL users: just push a new sources.json to GitHub.
    private const val OTA_URL =
        "https://raw.githubusercontent.com/Kousoyu/Drift/refs/heads/master/rules/sources.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ─── Built-in fallback ────────────────────────────────────────────────────
    // Used when: (1) first launch with no network, or (2) GitHub fetch fails.
    // Keep this in sync with a known-working snapshot of the remote JSON.
    private val FALLBACK_JSON = """
[
  {
    "name": "包子漫画",
    "baseUrl": "https://www.baozimh.org",
    "mirrorUrls": ["https://www.baozimh.com", "https://baozimh.com"],
    "enabled": true,
    "searchRule": {
      "popularFormat": "/",
      "urlFormat": "/?s={query}",
      "listSelector": "a[href^=/manga/]",
      "titleSelector": "@title",
      "coverSelector": "img@src",
      "urlSelector": "@href"
    },
    "detailRule": {
      "titleSelector": "h1@text",
      "coverSelector": "img.detail-info-cover@src",
      "authorSelector": "h2@text",
      "descSelector": "p@text",
      "statusSelector": "span.detail-info-tip-block-content@text",
      "chapterListSelector": "a[href^=/manga/][href*=-]",
      "chapterNameSelector": "@text",
      "chapterUrlSelector": "@href"
    },
    "chapterImagesRule": {
      "imageListSelector": "amp-img, img.comic-contain",
      "imageUrlSelector": "@src"
    }
  },
  {
    "name": "漫画库",
    "baseUrl": "https://www.manhuaku.com",
    "mirrorUrls": [],
    "enabled": false,
    "searchRule": {
      "popularFormat": "/rank/",
      "urlFormat": "/search/?keywords={query}",
      "listSelector": "div.book-list li",
      "titleSelector": "p.title@text",
      "coverSelector": "img@src",
      "urlSelector": "a@href"
    },
    "detailRule": {
      "titleSelector": "h1.book-title@text",
      "coverSelector": "div.book-cover img@src",
      "authorSelector": "a.book-author@text",
      "descSelector": "p.book-intro@text",
      "statusSelector": "span.book-status@text",
      "chapterListSelector": "ul.chapter-list a",
      "chapterNameSelector": "@text",
      "chapterUrlSelector": "@href"
    },
    "chapterImagesRule": {
      "imageListSelector": "div.reader-main img",
      "imageUrlSelector": "@src"
    }
  }
]
    """.trimIndent()

    // ─── Disk cache ───────────────────────────────────────────────────────────
    private fun cacheFile(context: Context) =
        File(context.filesDir, "drift_cached_sources.json")

    private fun readCache(context: Context): String? =
        runCatching { cacheFile(context).readText().ifBlank { null } }.getOrNull()

    private fun writeCache(context: Context, json: String) =
        runCatching { cacheFile(context).writeText(json) }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Loads remote sources with a 3-tier priority:
     *   1. Remote GitHub JSON (freshest)
     *   2. Locally cached JSON from last successful fetch
     *   3. Built-in FALLBACK_JSON (guaranteed to always work offline)
     *
     * Sources with `enabled: false` are silently skipped.
     */
    suspend fun fetchRemoteSources(context: Context, client: OkHttpClient = http): List<MangaSource> =
        withContext(Dispatchers.IO) {
            // Try remote first
            val remoteJson = try {
                val req = Request.Builder().url(OTA_URL).build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) res.body?.string()?.also { writeCache(context, it) } else null
            } catch (_: Exception) { null }

            val json = remoteJson ?: readCache(context) ?: FALLBACK_JSON

            parseSourcesJson(json, client)
        }

    // ─── JSON → DynamicMangaSource parser ────────────────────────────────────

    fun parseSourcesJson(json: String, client: OkHttpClient): List<MangaSource> {
        val sources = mutableListOf<MangaSource>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj     = array.getJSONObject(i)
                if (!obj.optBoolean("enabled", true)) continue  // skip disabled sources

                val search  = obj.getJSONObject("searchRule")
                val detail  = obj.getJSONObject("detailRule")
                val chapter = obj.getJSONObject("chapterImagesRule")

                // Parse optional mirrorUrls array
                val mirrors = mutableListOf<String>()
                obj.optJSONArray("mirrorUrls")?.let { arr ->
                    for (j in 0 until arr.length()) mirrors.add(arr.getString(j))
                }

                // Parse optional headers map
                val headers = mutableMapOf<String, String>()
                obj.optJSONObject("headers")?.let { hObj ->
                    hObj.keys().forEach { k -> headers[k] = hObj.getString(k) }
                }

                val config = DynamicSourceConfig(
                    name       = obj.getString("name"),
                    baseUrl    = obj.getString("baseUrl"),
                    mirrorUrls = mirrors,
                    headers    = headers,
                    enabled    = obj.optBoolean("enabled", true),
                    isR18      = obj.optBoolean("isR18", false),
                    searchRule = SearchRule(
                        popularFormat            = search.getString("popularFormat"),
                        urlFormat                = search.getString("urlFormat"),
                        listSelector             = search.getString("listSelector"),
                        titleSelector            = search.getString("titleSelector"),
                        coverSelector            = search.getString("coverSelector"),
                        urlSelector              = search.getString("urlSelector"),
                        detailUrlPrefix          = search.optString("detailUrlPrefix").ifEmpty { null },
                        detailUrlSuffix          = search.optString("detailUrlSuffix").ifEmpty { null },
                        latestChapterSelector    = search.optString("latestChapterSelector").ifEmpty { null },
                        genreSelector            = search.optString("genreSelector").ifEmpty { null },
                        authorSelector           = search.optString("authorSelector").ifEmpty { null }
                    ),
                    detailRule = DetailRule(
                        titleSelector       = detail.getString("titleSelector"),
                        coverSelector       = detail.getString("coverSelector"),
                        authorSelector      = detail.getString("authorSelector"),
                        descSelector        = detail.getString("descSelector"),
                        statusSelector      = detail.getString("statusSelector"),
                        chapterListSelector = detail.getString("chapterListSelector"),
                        chapterNameSelector = detail.getString("chapterNameSelector"),
                        chapterUrlSelector  = detail.getString("chapterUrlSelector"),
                        chapterListUrl      = detail.optString("chapterListUrl").ifEmpty { null }
                    ),
                    chapterImagesRule = ChapterImagesRule(
                        imageListSelector = chapter.getString("imageListSelector"),
                        imageUrlSelector  = chapter.getString("imageUrlSelector"),
                        imageBaseUrl      = chapter.optString("imageBaseUrl").ifEmpty { null }
                    )
                )
                sources.add(DynamicMangaSource(config, client))
            }
        }.onFailure { it.printStackTrace() }
        return sources
    }
}

