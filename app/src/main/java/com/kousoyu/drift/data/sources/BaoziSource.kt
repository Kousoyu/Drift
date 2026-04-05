package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONObject

/**
 * DEPRECATED — no longer registered in SourceManager.
 *
 * Baozi is now fully driven by the remote JSON rule in rules/sources.json via DynamicMangaSource.
 * This file is kept for reference only (specifically its mgsearcher.com internal API approach
 * which may be useful for a future premium API-first channel).
 *
 * To update Baozi's parsing logic: edit rules/sources.json in the GitHub repo — no APK needed.
 */
@Deprecated("Replaced by DynamicMangaSource(baozi config). Edit rules/sources.json on GitHub instead.")
class BaoziSource(private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient()) : MangaSource {
    override val name = "包子漫画"
    override val baseUrl = "https://www.baozimh.org"
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.connect(baseUrl).headers(headers).get()
            val elements = doc.select("a[href^=/manga/]")
            val list = mutableListOf<Manga>()
            
            for (el in elements) {
                val title = el.attr("title")
                val href = el.attr("href")
                if (title.isBlank() || href.isBlank() || href.count { it == '-' } > 1) continue
                
                val imgEl = el.selectFirst("img") ?: continue
                val coverUrl = imgEl.attr("src")
                if (coverUrl.isBlank()) continue
                
                list.add(Manga(
                    title = title,
                    coverUrl = coverUrl,
                    detailUrl = if (href.startsWith("http")) href else "$baseUrl$href",
                    sourceName = name
                ))
            }
            list.distinctBy { it.detailUrl }.take(30)
        }
    }

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val searchUrl = "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val doc = Jsoup.connect(searchUrl).headers(headers).get()
            val elements = doc.select("a[href^=/manga/]")
            val list = mutableListOf<Manga>()
            
            for (el in elements) {
                val title = el.attr("title")
                val href = el.attr("href")
                if (title.isBlank() || href.isBlank() || href.count { it == '-' } > 1) continue
                
                val imgEl = el.selectFirst("img")
                val coverUrl = imgEl?.attr("src") ?: ""
                
                list.add(Manga(
                    title = title,
                    coverUrl = coverUrl,
                    detailUrl = if (href.startsWith("http")) href else "$baseUrl$href",
                    sourceName = name
                ))
            }
            list.distinctBy { it.detailUrl }
        }
    }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val doc = Jsoup.connect(detailUrl).headers(headers).get()
                
                // Baozi sometimes puts chapters directly in detail page, sometimes in chapterlist.
                // We'll scrape all links that look like a chapter link: /manga/slug/mID-cID-part
                var chapLinks = doc.select("a[href~=^/manga/[^/]+/[0-9]+-[0-9]+-[0-9]+$]")
                if (chapLinks.isEmpty()) {
                    // Try chapterlist API
                    val slug = detailUrl.substringAfterLast("/")
                    val chapListUrl = "$baseUrl/chapterlist/$slug"
                    val chapDoc = Jsoup.connect(chapListUrl).headers(headers).get()
                    chapLinks = chapDoc.select("a[href~=^/manga/[^/]+/[0-9]+-[0-9]+-[0-9]+$]")
                }

                val chapters = chapLinks.map {
                    MangaChapter(
                        name = it.text().ifBlank { "章节" },
                        url = if (it.attr("href").startsWith("http")) it.attr("href") else "$baseUrl${it.attr("href")}"
                    )
                }.reversed() // Usually earliest first in source, reverse to put latest at top
                
                val desc = doc.select("p.text-sm.text-gray-500").text()
                val title = doc.select("h1").text()
                val author = doc.select("h2.text-sm.font-normal.text-gray-500").text() // Optional author fallback
                MangaDetail(
                    url = detailUrl,
                    title = title,
                    coverUrl = "",
                    author = author,
                    description = desc,
                    status = "",
                    chapters = chapters
                )
            }
        }
    }

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                // chapterUrl format: .../manga/slug/12345-67890-1
                val idMatch = Regex("""/(\d+)-(\d+)-\d+$""").find(chapterUrl)
                    ?: return@withContext Result.failure(Exception("Cannot extract chapter IDs from URL: $chapterUrl"))
                
                val mId = idMatch.groupValues[1]
                val cId = idMatch.groupValues[2]
                
                val apiUrl = "https://api-get-v3.mgsearcher.com/api/chapter/getinfo?m=$mId&c=$cId"
                val apiDoc = Jsoup.connect(apiUrl).headers(headers).ignoreContentType(true).execute().body()
                
                val jsonObj = JSONObject(apiDoc)
                if (jsonObj.optInt("code") != 200) {
                    return@withContext Result.failure(Exception("API returned non-200 code"))
                }
                
                val imagesArray = jsonObj.getJSONObject("data").getJSONObject("info").getJSONObject("images").getJSONArray("images")
                
                val urls = mutableListOf<String>()
                val imgCdn = "https://t40-2-4.g-mh.online"
                for (i in 0 until imagesArray.length()) {
                    val item = imagesArray.getJSONObject(i)
                    val relUrl = item.getString("url")
                    urls.add("$imgCdn$relUrl")
                }
                urls
            }
        }
    }
}
