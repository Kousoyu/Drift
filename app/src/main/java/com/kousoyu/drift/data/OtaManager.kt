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
    // This URL will be replaced by the raw github URL once committed!
    private const val OTA_URL = "https://raw.githubusercontent.com/xiaomi/Drift/main/rules/sources.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchRemoteSources(): List<MangaSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<MangaSource>()
        try {
            val request = Request.Builder().url(OTA_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            
            val jsonString = response.body?.string() ?: return@withContext emptyList()
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
