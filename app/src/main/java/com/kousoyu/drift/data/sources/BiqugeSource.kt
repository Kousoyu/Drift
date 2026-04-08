package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 笔趣阁 — Fast novel source, no Cloudflare.
 *
 * Mobile site (m.bqg70.com) → home page, search
 * PC site (www.bqg70.com)   → detail + chapters (mobile has no chapter list!)
 */
class BiqugeSource(
    private val client: OkHttpClient
) : NovelSource {

    override val name    = "笔趣阁"
    override val baseUrl = "https://m.bqg70.com"
    private val pcBase   = "https://www.bqg70.com"

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ─── HTTP Fetch ─────────────────────────────────────────────────────────

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
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
        val html = fetch("$baseUrl/")
        val doc = Jsoup.parse(html, baseUrl)

        val novels = mutableListOf<NovelItem>()
        val skip = setOf("首页", "排行榜", "全本", "我的书架", "阅读记录", "网站地图",
            "加入书架", "开始阅读", "更新报错", "查看更多章节>>", "xml")

        // Cover map from <img> tags
        val coverMap = mutableMapOf<String, String>()
        doc.select("img[src*=bookimg]").forEach { img ->
            val src = img.absUrl("src")
            val alt = img.attr("alt").trim()
            if (alt.isNotEmpty()) coverMap[alt] = src
        }

        doc.select("a[href~=/book/\\d+/\$]").forEach { a ->
            val href = a.absUrl("href")
            val title = a.text().trim()
            if (title.length < 2 || title in skip) return@forEach

            val author = a.parent()?.selectFirst(".s3")?.text()?.trim() ?: ""

            novels.add(NovelItem(
                title = title,
                author = author,
                coverUrl = coverMap[title] ?: "",
                // Store PC detail URL for chapter list access
                detailUrl = href.replace("m.bqg70.com", "www.bqg70.com"),
                sourceName = name
            ))
        }

        novels.distinctBy { it.detailUrl }.take(30)
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchNovel(query: String): Result<List<NovelItem>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = fetch("$baseUrl/s?q=$q")
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("a[href~=/book/\\d+/\$]").mapNotNull { a ->
            val href = a.absUrl("href")
            val title = a.text().trim()
            if (title.length < 2) return@mapNotNull null

            NovelItem(
                title = title,
                author = "",
                coverUrl = a.parent()?.selectFirst("img")?.absUrl("src") ?: "",
                detailUrl = href.replace("m.bqg70.com", "www.bqg70.com"),
                sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail (uses PC site — has full chapter list!) ─────────────────────

    override suspend fun getNovelDetail(detailUrl: String): Result<NovelDetail> = runCatching {
        // Ensure PC URL
        val pcUrl = detailUrl.replace("m.bqg70.com", "www.bqg70.com")
        val html = fetch(pcUrl)
        val doc = Jsoup.parse(html, pcBase)

        val title = doc.title()
            .substringBefore("最新").substringBefore("_").trim()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim() ?: "未知" }

        val desc = doc.selectFirst("meta[name=description], meta[property=og:description]")
            ?.attr("content")?.trim() ?: ""
        val coverUrl = doc.selectFirst("img[src*=bookimg]")?.absUrl("src") ?: ""

        // Extract book ID for filtering chapter links
        val bookId = pcUrl.trimEnd('/').substringAfterLast("/book/").substringBefore("/")

        // Parse ALL chapter links from the PC detail page
        val chapters = doc.select("a[href~=/book/$bookId/\\d+\\.html]").mapNotNull { a ->
            val href = a.absUrl("href")
            val chName = a.text().trim()
            if (chName.isBlank() || chName.length < 2) return@mapNotNull null
            // Skip nav links
            if (chName in setOf("开始阅读", "加入书架", "更新报错", "直达底部", "<<---展开全部章节--->>")) return@mapNotNull null
            NovelChapter(name = chName, url = href)
        }.distinctBy { it.url }

        val volumes = if (chapters.isNotEmpty()) {
            listOf(NovelVolume(name = title, chapters = chapters))
        } else emptyList()

        NovelDetail(
            url = pcUrl,
            title = title, author = "", coverUrl = coverUrl,
            description = desc, status = "",
            volumes = volumes
        )
    }

    // ─── Chapter Content ────────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): Result<String> = runCatching {
        val html = fetch(chapterUrl)
        val doc = Jsoup.parse(html, pcBase)

        val content = doc.selectFirst("#chaptercontent, #content, .chapter-content, .readcontent")
            ?: error("未找到章节内容")

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
        "Referer" to "$pcBase/"
    )
}
