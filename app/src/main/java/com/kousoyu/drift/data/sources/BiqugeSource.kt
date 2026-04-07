package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 笔趣阁 (bqg70.com) — Fast novel source.
 *
 * ✅ No Cloudflare   → pure OkHttp, no WebView needed
 * ✅ Simple HTML     → reliable Jsoup parsing
 * ✅ Rich library    → 100k+ novels
 * ✅ Instant loading → <0.5s per request
 *
 * URL patterns:
 *   Home:    https://m.bqg70.com/
 *   Detail:  https://m.bqg70.com/book/{id}/
 *   Chapter: https://m.bqg70.com/book/{id}/{num}.html
 *   Search:  https://m.bqg70.com/s?q={query}
 *   List:    https://m.bqg70.com/book/{id}/list.html
 */
class BiqugeSource(
    private val client: OkHttpClient
) : NovelSource {

    override val name    = "笔趣阁"
    override val baseUrl = "https://m.bqg70.com"

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ─── HTTP Fetch (fast, no WebView!) ──────────────────────────────────────

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$baseUrl$path"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response")
        }
    }

    // ─── Popular ────────────────────────────────────────────────────────────

    override suspend fun getPopularNovels(): Result<List<NovelItem>> = runCatching {
        val html = fetch("/")
        val doc = Jsoup.parse(html, baseUrl)

        val novels = mutableListOf<NovelItem>()

        // Parse recommendation sections
        doc.select("a[href*=/book/]").forEach { a ->
            val href = a.absUrl("href")
            if (!href.matches(Regex(".*bqg70\\.com/book/\\d+/$"))) return@forEach
            val title = a.text().trim()
            if (title.length < 2) return@forEach
            // Skip navigation links
            val skip = setOf("首页", "排行榜", "全本", "我的书架", "阅读记录", "网站地图",
                "加入书架", "开始阅读", "更新报错", "查看更多章节>>")
            if (title in skip) return@forEach

            novels.add(NovelItem(
                title = title,
                author = "",
                coverUrl = "",
                detailUrl = href,
                sourceName = name
            ))
        }

        // Deduplicate
        novels.distinctBy { it.detailUrl }.take(30)
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchNovel(query: String): Result<List<NovelItem>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = fetch("/s?q=$q")
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("a[href*=/book/]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (!href.matches(Regex(".*bqg70\\.com/book/\\d+/$"))) return@mapNotNull null
            val title = a.text().trim()
            if (title.length < 2) return@mapNotNull null
            NovelItem(
                title = title, author = "", coverUrl = "",
                detailUrl = href, sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail ─────────────────────────────────────────────────────────────

    override suspend fun getNovelDetail(detailUrl: String): Result<NovelDetail> = runCatching {
        val html = fetch(detailUrl)
        val doc = Jsoup.parse(html, baseUrl)

        val title = doc.selectFirst("h1, .bookTitle, .book-title")?.text()?.trim()
            ?: doc.title().substringBefore("最新").substringBefore("_").trim()
        val desc = doc.selectFirst("meta[name=description], meta[property=og:description]")
            ?.attr("content")?.trim() ?: ""

        // Get full chapter list
        val bookId = detailUrl.trimEnd('/').substringAfterLast("/book/").substringBefore("/")
        val listHtml = fetch("/book/$bookId/list.html")
        val listDoc = Jsoup.parse(listHtml, baseUrl)

        val chapters = listDoc.select("a[href*=/book/$bookId/]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (!href.endsWith(".html")) return@mapNotNull null
            val name = a.text().trim()
            if (name.isBlank()) return@mapNotNull null
            NovelChapter(name = name, url = href)
        }.distinctBy { it.url }

        val volumes = if (chapters.isNotEmpty()) {
            listOf(NovelVolume(name = title, chapters = chapters))
        } else emptyList()

        NovelDetail(
            url = detailUrl,
            title = title,
            author = "",
            coverUrl = "",
            description = desc,
            status = "",
            volumes = volumes
        )
    }

    // ─── Chapter Content ────────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): Result<String> = runCatching {
        val html = fetch(chapterUrl)
        val doc = Jsoup.parse(html, baseUrl)

        val content = doc.selectFirst("#chaptercontent, #content, .chapter-content, .readcontent")
            ?: error("未找到章节内容")

        // Clean ads and formatting
        content.select("script, ins, .adsbygoogle, style, .readinline, p.readinline").remove()
        content.select("br").append("\n")
        content.select("p").prepend("\n")

        content.text()
            .replace(Regex("请收藏.*?笔趣阁"), "")
            .replace(Regex("笔趣阁.*?bqg.*?\\.com"), "")
            .replace(Regex("http[s]?://[^ ]+"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    override fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$baseUrl/"
    )
}
