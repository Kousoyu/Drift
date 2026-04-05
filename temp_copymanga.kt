package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Scraper implementation for CopyManga (鎷疯礉婕敾)
 * Domain: www.mangacopy.com
 *
 * HTML structure (verified from live site):
 *   Popular list  : div.col-auto.links-of-recommend-goods-item  
 *                   鈫?a[href=/comic/xxx/]
 *                   鈫?img.lazyload[data-src=CDN url]
 *                   鈫?p.card-comic-title
 *   Search endpoint: GET /search?q={keyword}&limit=9&offset=0&platform=4
 *   Detail page   : /comic/{path-word}/
 *   Chapter images: embedded in page JS as JSON array under window.__NEXT_DATA__ or direct img tags
 */
class CopyMangaSource : MangaSource {
    override val name = "鎷疯礉婕敾"
    override val baseUrl = "https://www.mangacopy.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build()
            chain.proceed(req)
        }
        .build()

    // 鈹€鈹€鈹€ Popular 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiUrl = "https://api.mangacopy.com/api/v3/ranks?limit=15"
                val json = fetch(apiUrl)
                val root = org.json.JSONObject(json)
                val list = root.getJSONObject("results").getJSONArray("list")
                val results = mutableListOf<Manga>()
                for (i in 0 until list.length()) {
                    val comic = list.getJSONObject(i).optJSONObject("comic") ?: continue
                    
                    val authors = comic.optJSONArray("author")
                    val authorName = if (authors != null && authors.length() > 0) {
                        authors.getJSONObject(0).optString("name", "")
                    } else ""
                    
                    results.add(
                        Manga(
                            title = comic.getString("name"),
                            coverUrl = comic.getString("cover"),
                            detailUrl = "$baseUrl/comic/${comic.getString("path_word")}/",
                            latestChapter = "",
                            genre = "",
                            author = authorName,
                            sourceName = name
                        )
                    )
                }
                results
            }
        }

    // 鈹€鈹€鈹€ Search (JSON API 鈥?bypasses Cloudflare 5-second shield on HTML path) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val apiUrl = "https://api.mangacopy.com/api/v3/search/comic?platform=3&limit=15&offset=0&q=$encoded"
                val body = fetch(apiUrl)
                parseSearchJson(body)
            }
        }

    // 鈹€鈹€鈹€ Detail 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(detailUrl)
                val doc = Jsoup.parse(html)

                val title = doc.selectFirst("h6.comicParticulars-title-right")?.text()?.trim()
                    ?: doc.selectFirst("h1, h2, .comic-title")?.text()?.trim()
                    ?: "鏈煡鏍囬"

                val cover = doc.selectFirst("div.comicParticulars-left-img img, img.lazyload")
                    ?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                val author = doc.select("span.comicParticulars-tag-title a").firstOrNull()?.text()?.trim()
                    ?: doc.selectFirst(".author a, .comic-author")?.text()?.trim() ?: ""

                val desc = doc.selectFirst("p.intro-all, div.comic-introduction p, .intro")
                    ?.text()?.trim() ?: "鏆傛棤绠€浠?

                val status = doc.selectFirst("span.comicParticulars-title-right-tag")
                    ?.text()?.trim() ?: "杩炶浇涓?

                // Chapter list: ul.comic-chapters-box a or similar
                val chapters = doc.select("div.chapterSon a, div.comicWorksInfo-box a[href*=chapter], ul.comic-chapters-box a").mapNotNull { a ->
                    val href = a.attr("href")
                    val chName = a.text().trim().ifEmpty {
                        a.selectFirst("span")?.text()?.trim() ?: ""
                    }
                    if (href.isNotEmpty() && chName.isNotEmpty()) {
                        MangaChapter(chName, if (href.startsWith("http")) href else "$baseUrl$href")
                    } else null
                }.distinctBy { it.url }.reversed()

                MangaDetail(detailUrl, title, cover, author, desc, status, chapters)
            }
        }

    // 鈹€鈹€鈹€ Chapter Images 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(chapterUrl)
                val doc = Jsoup.parse(html)

                // CopyManga renders images with img tags or data-src
                var urls = doc.select("img.comic-image, div.comicContent-list img")
                    .mapNotNull { it.attr("data-src").ifEmpty { it.attr("src") }.takeIf { url -> url.isNotEmpty() } }

                if (urls.isEmpty()) {
                    // Fallback: any img from their CDN
                    urls = doc.select("img").mapNotNull {
                        val src = it.attr("data-src").ifEmpty { it.attr("src") }
                        src.takeIf { s -> s.contains("mangafunb") || s.contains("mangacopy") }
                    }
                }

                if (urls.isEmpty()) error("鎷疯礉婕敾锛氭湭鎵惧埌鍥剧墖 URL锛屽彲鑳介〉闈㈢粨鏋勫凡鍙樻洿")
                urls
            }
        }


    // 鈹€鈹€鈹€ Search JSON Parser (api.mangacopy.com) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    //
    // Response shape:
    // {"code":200,"results":{"list":[{"name":"...","path_word":"...","cover":"..."}]}}
    //
    private fun parseSearchJson(json: String): List<Manga> {
        val namePattern   = Regex(""""name"\s*:\s*"([^"]+)"""")
        val pathPattern   = Regex(""""path_word"\s*:\s*"([^"]+)"""")
        val coverPattern  = Regex(""""cover"\s*:\s*"([^"]+)"""")
        val authorPattern = Regex(""""author"\s*:\s*\[\s*\{[^}]*"name"\s*:\s*"([^"]+)"""")

        // Parse parallel lists from the JSON string (order-sensitive regex walk)
        val names   = namePattern.findAll(json).map { it.groupValues[1] }.toList()
        val paths   = pathPattern.findAll(json).map { it.groupValues[1] }.toList()
        val covers  = coverPattern.findAll(json).map { it.groupValues[1] }.toList()
        val authors = authorPattern.findAll(json).map { it.groupValues[1] }.toList()

        return names.indices.mapNotNull { i ->
            val path = paths.getOrNull(i) ?: return@mapNotNull null
            Manga(
                title         = names[i],
                coverUrl      = covers.getOrNull(i) ?: "",
                detailUrl     = "$baseUrl/comic/$path/",
                latestChapter = "",
                genre         = "",
                sourceName    = name,
                author        = authors.getOrNull(i) ?: ""
            )
        }
    }

    // 鈹€鈹€鈹€ HTTP 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
        return resp.body!!.string()
    }
}
