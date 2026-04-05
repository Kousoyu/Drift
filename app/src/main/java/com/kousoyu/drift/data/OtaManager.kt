package com.kousoyu.drift.data

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
import java.util.concurrent.TimeUnit

object OtaManager {
    private const val OTA_URL = "https://raw.githubusercontent.com/Kousoyu/Drift/master/rules/sources.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    // Built-in fallback JSON to ensure the app works even if offline or GitHub Raw is blocked
    private val FALLBACK_JSON = """
    [
      {
        "name": "包子漫画",
        "baseUrl": "https://www.baozimh.com",
        "searchRule": {
            "popularFormat": "/",
            "urlFormat": "/search?type=all&q={query}",
            "listSelector": "div.comics-card",
            "titleSelector": "h3@text",
            "coverSelector": "amp-img@src",
            "urlSelector": "a.comics-card__poster@href"
        },
        "detailRule": {
            "titleSelector": "h1.comics-detail__title@text",
            "coverSelector": "amp-img@src",
            "authorSelector": "h2.comics-detail__author@text",
            "descSelector": "p.comics-detail__desc@text",
            "statusSelector": "div.tag-list span.tag@text",
            "chapterListSelector": "div.comics-chapters a",
            "chapterNameSelector": "span@text",
            "chapterUrlSelector": "@href"
        },
        "chapterImagesRule": {
            "imageListSelector": "amp-img.comic-contain__item, img.comic-contain__item",
            "imageUrlSelector": "@src"
        }
      }
    ]
    """.trimIndent()

    suspend fun fetchRemoteSources(): List<MangaSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<MangaSource>()
        var jsonString = FALLBACK_JSON
        try {
            val request = Request.Builder().url(OTA_URL).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                jsonString = response.body?.string() ?: FALLBACK_JSON
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // network failed, fallback jsonString is preserved
        }
        
        try {
            val array = JSONArray(jsonString)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val searchObj = obj.getJSONObject("searchRule")
                val detailObj = obj.getJSONObject("detailRule")
                val chapObj = obj.getJSONObject("chapterImagesRule")

                val config = DynamicSourceConfig(
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    searchRule = SearchRule(
                        popularFormat = searchObj.getString("popularFormat"),
                        urlFormat = searchObj.getString("urlFormat"),
                        listSelector = searchObj.getString("listSelector"),
                        titleSelector = searchObj.getString("titleSelector"),
                        coverSelector = searchObj.getString("coverSelector"),
                        urlSelector = searchObj.getString("urlSelector"),
                        detailUrlPrefix = searchObj.optString("detailUrlPrefix", "").ifEmpty { null },
                        detailUrlSuffix = searchObj.optString("detailUrlSuffix", "").ifEmpty { null },
                        latestChapterSelector = searchObj.optString("latestChapterSelector", "").ifEmpty { null },
                        genreSelector = searchObj.optString("genreSelector", "").ifEmpty { null },
                        authorSelector = searchObj.optString("authorSelector", "").ifEmpty { null }
                    ),
                    detailRule = DetailRule(
                        titleSelector = detailObj.getString("titleSelector"),
                        coverSelector = detailObj.getString("coverSelector"),
                        authorSelector = detailObj.getString("authorSelector"),
                        descSelector = detailObj.getString("descSelector"),
                        statusSelector = detailObj.getString("statusSelector"),
                        chapterListSelector = detailObj.getString("chapterListSelector"),
                        chapterNameSelector = detailObj.getString("chapterNameSelector"),
                        chapterUrlSelector = detailObj.getString("chapterUrlSelector")
                    ),
                    chapterImagesRule = ChapterImagesRule(
                        imageListSelector = chapObj.getString("imageListSelector"),
                        imageUrlSelector = chapObj.getString("imageUrlSelector")
                    )
                )

                sources.add(DynamicMangaSource(config, client))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sources
    }
}
