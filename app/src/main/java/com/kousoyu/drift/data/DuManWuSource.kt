package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

/**
 * Scraper for 读漫屋 (dumanwu1.com)
 *
 * Note: Site is a traditional Chinese comic aggregator. The main page appears
 * to be fairly standard server-side rendered HTML with traditional Chinese content.
 * Structure probed: 200 OK, 14914 bytes, 42 imgs, 37 links.
 * The site may have comic listings under /list/ or /rank/ paths.
 */
class DuManWuSource : MangaSource {
    override val name = "读漫屋"
    override val baseUrl = "https://www.dumanwu.com"   // primary; http version often redirects

    // Site is known to change domain frequently
    private val domains = listOf(
        "https://www.dumanwu.com",
        "http://dumanwu1.com",
        "https://dumanwu.com"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(DriftDns)   // ← DoH bypass for any DNS interference
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .header("Accept-Language", "zh-TW,zh;q=0.9,zh-CN;q=0.8")
                .build()
            chain.proceed(req)
        }
        .build()

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // The homepage itself has the manga listings
                val html = fetch(baseUrl)
                val result = parseMangaList(html)
                if (result.isEmpty()) error("读漫屋：首页解析结果为空，网站结构可能已变更")
                result
            }
        }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                // Try each domain with multiple known search path patterns
                val candidates = domains.flatMap { domain ->
                    listOf(
                        "$domain/?s=$encoded",
                        "$domain/search/$encoded/",
                        "$domain/search?keyword=$encoded",
                        "$domain/?search=$encoded",
                        "$domain/index.php?s=search&q=$encoded"
                    )
                }
                var lastErr: Exception = Exception("未找到搜索端点")
                for (url in candidates) {
                    try {
                        val html = fetchMulti(url)
                        val results = parseMangaList(html)
                        if (results.isNotEmpty()) return@runCatching results
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                throw lastErr
            }
        }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(detailUrl)
                val doc = Jsoup.parse(html)

                val title = doc.selectFirst("h1.book-title, h1.comic-title, h1")
                    ?.text()?.trim() ?: "未知标题"

                val cover = doc.selectFirst("img.book-cover, div.book-cover img, .comic-cover img")
                    ?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                val author = doc.selectFirst(".author a, .book-author, span.author")
                    ?.text()?.trim() ?: ""

                val desc = doc.selectFirst(".intro, .book-intro, .comic-desc, p.description")
                    ?.text()?.trim() ?: "暂无简介"

                val status = doc.selectFirst(".status, .book-status, span.state")
                    ?.text()?.trim() ?: "连载中"

                val chapters = doc.select("a[href*='/chapter/'], a[href*='/read/'], ul.chapter-list a").mapNotNull { a ->
                    val href = a.attr("href")
                    val chName = a.text().trim()
                    if (href.isNotEmpty() && chName.isNotEmpty()) {
                        MangaChapter(chName, if (href.startsWith("http")) href else "$baseUrl$href")
                    } else null
                }.distinctBy { it.url }

                MangaDetail(detailUrl, title, cover, author, desc, status, chapters)
            }
        }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(chapterUrl)
                val doc = Jsoup.parse(html)

                val urls = doc.select("div.read-main img, div.chapter-reader img, #readerBox img")
                    .mapNotNull { it.attr("data-src").ifEmpty { it.attr("src") }.takeIf { s -> s.isNotEmpty() } }

                if (urls.isEmpty()) error("读漫屋：未找到图片，页面结构可能已变更")
                urls
            }
        }

    // ─── Parser ────────────────────────────────────────────────────────────────

    private fun parseMangaList(html: String): List<Manga> {
        val doc = Jsoup.parse(html)

        // Try multiple container patterns common in Chinese manga sites
        val cards = doc.select(
            "div.book-item, div.comic-item, li.comic-li, li.book-li, " +
            "div.rank-item, div.manga-item, ul.book-list li"
        )

        return cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
                .let { if (it.startsWith("http")) it else "$baseUrl$it" }

            val img = card.selectFirst("img")
            val cover = img?.attr("data-src")?.ifEmpty { null }
                ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("default") }
                ?: return@mapNotNull null

            val title = card.selectFirst("p.title, span.title, .book-title, h3, h4")
                ?.text()?.trim()
                ?: link.attr("title").trim().ifEmpty { null }
                ?: return@mapNotNull null

            Manga(
                title = title,
                coverUrl = cover,
                detailUrl = href,
                latestChapter = card.selectFirst(".last-chapter, .update, span.chapter")
                    ?.text()?.trim() ?: "",
                genre = card.selectFirst(".genre, .tag, span.type")?.text()?.trim() ?: "",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }.take(20)
    }

    // ─── HTTP with domain fallback ──────────────────────────────────────────────────────

    private fun fetchMulti(url: String): String {
        // Try the URL as given first, then remap to other known domains
        val originalDomain = domains.firstOrNull { url.startsWith(it) } ?: baseUrl
        val path = url.removePrefix(originalDomain)
        var lastErr: Exception = IllegalStateException("No domains")
        for (domain in domains) {
            val target = domain + path
            try {
                val req = Request.Builder().url(target).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return resp.body!!.string()
                lastErr = IllegalStateException("HTTP ${resp.code} @ $target")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
        return resp.body!!.string()
    }
}
