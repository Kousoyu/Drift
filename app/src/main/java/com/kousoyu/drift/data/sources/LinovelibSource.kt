package com.kousoyu.drift.data.sources

import android.content.Context
import com.kousoyu.drift.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * 哔哩轻小说 (linovelib.com) — Light Novel source.
 *
 * Uses CloudflareBypass (WebView) for all requests —
 * no cookie management needed, just direct HTML extraction.
 *
 * URL patterns:
 *   Popular:  /top/monthvisit/1.html
 *   Search:   /search.html?searchkey={query}
 *   Detail:   /novel/{id}.html
 *   Catalog:  /novel/{id}/catalog
 *   Chapter:  /novel/{id}/{chapterId}.html
 */
class LinovelibSource(
    private val client: OkHttpClient,
    context: Context
) : NovelSource {

    override val name    = "哔哩轻小说"
    override val baseUrl = "https://www.linovelib.com"

    private val cfBypass = CloudflareBypass(context.applicationContext)

    // ─── Pre-warm ───────────────────────────────────────────────────────────

    /** Pre-solve Cloudflare so novel tab loads instantly. */
    suspend fun preWarm() = cfBypass.preWarm(baseUrl)

    // ─── WebView-based fetch ────────────────────────────────────────────────

    private suspend fun fetch(path: String): String {
        val url = if (path.startsWith("http")) path
                  else "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        return cfBypass.fetchHtml(url)
    }

    // ─── Popular ────────────────────────────────────────────────────────────

    override suspend fun getPopularNovels(): Result<List<NovelItem>> = runCatching {
        val html = fetch("/top/monthvisit/1.html")
        parseNovelList(html)
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchNovel(query: String): Result<List<NovelItem>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = fetch("/search.html?searchkey=$q")
        parseNovelList(html)
    }

    // ─── Detail ─────────────────────────────────────────────────────────────

    override suspend fun getNovelDetail(detailUrl: String): Result<NovelDetail> = runCatching {
        val html = fetch(detailUrl)
        val doc = Jsoup.parse(html, baseUrl)

        // Multiple fallback selectors for resilience
        val title  = sel(doc, "h1", "h2.book-title", ".book-title") ?: "未知标题"
        val author = sel(doc, ".book-rand-a a", ".author a", "span:contains(作者) + a") ?: ""
        val cover  = selAttr(doc, "src", ".book-img img", ".book-cover img")
            ?: selAttr(doc, "content", "meta[property=og:image]") ?: ""
        val desc   = sel(doc, ".book-dec p", ".book-info-detail .book-dec",
                         "meta[name=description]") ?: ""
        val status = sel(doc, ".book-label span", "span.state", ".book-label .color1") ?: ""

        // Fetch catalog
        val novelId = detailUrl.substringAfterLast("/novel/").substringBefore(".html")
        val catalogHtml = fetch("/novel/$novelId/catalog")
        val catalogDoc = Jsoup.parse(catalogHtml, baseUrl)

        val volumes = parseCatalog(catalogDoc, title)

        NovelDetail(
            url = detailUrl,
            title = title,
            author = author,
            coverUrl = absUrl(cover),
            description = desc,
            status = status,
            volumes = volumes
        )
    }

    // ─── Chapter Content ────────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): Result<String> = runCatching {
        val html = fetch(chapterUrl)
        val doc = Jsoup.parse(html, baseUrl)

        val content = doc.selectFirst("#acontent, .read-content, .chapter-content, #TextContent")
            ?: error("未找到章节内容")

        // Clean up
        content.select("script, .google-auto-placed, .adsbygoogle, ins, .tp-box, style").remove()
        content.select("br").append("\n")
        content.select("p").prepend("\n")

        content.text()
            .replace("\\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    override fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to CloudflareBypass.UA,
        "Referer" to "$baseUrl/"
    )

    // ─── Parse Logic ────────────────────────────────────────────────────────

    private fun parseNovelList(html: String): List<NovelItem> {
        val doc = Jsoup.parse(html, baseUrl)

        // Strategy 1: Structured book items
        val bookItems = doc.select(".book-li, .rank-book-list li, .book-layout, .book-item")
        if (bookItems.isNotEmpty()) {
            return bookItems.mapNotNull { el ->
                val linkEl = el.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
                val title = el.selectFirst("h4, h3, h2, .book-title")?.text()?.trim()
                    ?: linkEl.text().trim()
                if (title.isBlank() || title.length < 2) return@mapNotNull null

                val href = absUrl(linkEl.attr("href"))
                if (!href.contains("/novel/")) return@mapNotNull null

                val cover = el.selectFirst("img")?.let {
                    absUrl(it.attr("src").ifEmpty { it.attr("data-src") })
                } ?: ""
                val author = el.selectFirst(".author a, .book-author")?.text()?.trim() ?: ""
                val status = el.selectFirst(".book-label span, .status")?.text()?.trim() ?: ""

                NovelItem(
                    title = title, author = author, coverUrl = cover,
                    detailUrl = href, status = status, sourceName = name
                )
            }.distinctBy { it.detailUrl }
        }

        // Strategy 2: Generic link extraction (less reliable but catches edge cases)
        return doc.select("a[href*=/novel/]").mapNotNull { a ->
            val href = absUrl(a.attr("href"))
            if (!href.matches(Regex(".*/novel/\\d+\\.html$"))) return@mapNotNull null
            val title = a.text().trim()
            if (title.length < 2) return@mapNotNull null
            val skipList = setOf("首页", "分类", "排行", "全本", "文库", "登录", "注册",
                                 "立即阅读", "开始阅读", "更多")
            if (title in skipList) return@mapNotNull null

            NovelItem(
                title = title, author = "", coverUrl = "",
                detailUrl = href, sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    private fun parseCatalog(doc: org.jsoup.nodes.Document, fallbackTitle: String): List<NovelVolume> {
        val volumes = mutableListOf<NovelVolume>()

        // Try structured volume/chapter layout
        val chapterLists = doc.select(".chapter-list, .volume-list, ol.chapter-bar-list")
        if (chapterLists.isNotEmpty()) {
            chapterLists.forEach { volEl ->
                val volName = volEl.previousElementSibling()?.text()?.trim()
                    ?: volEl.attr("data-volume-name").ifEmpty { null }
                    ?: "未知卷"
                val chapters = volEl.select("li a, a.chapter-li-a").mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    NovelChapter(name = a.text().trim(), url = absUrl(href))
                }
                if (chapters.isNotEmpty()) {
                    volumes.add(NovelVolume(name = volName, chapters = chapters))
                }
            }
        }

        // Fallback: all links that look like chapter URLs
        if (volumes.isEmpty()) {
            val allChapters = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains("/novel/") || href.endsWith("/catalog") || href.endsWith(".html").not()) return@mapNotNull null
                // Chapters have a deeper path than detail pages
                val path = href.substringAfter("/novel/")
                if (!path.contains("/")) return@mapNotNull null
                val text = a.text().trim()
                if (text.isBlank() || text.length < 2) return@mapNotNull null
                NovelChapter(name = text, url = absUrl(href))
            }.distinctBy { it.url }

            if (allChapters.isNotEmpty()) {
                volumes.add(NovelVolume(name = fallbackTitle, chapters = allChapters))
            }
        }

        return volumes
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun sel(doc: org.jsoup.nodes.Document, vararg sels: String): String? =
        sels.firstNotNullOfOrNull { doc.selectFirst(it)?.text()?.trim()?.ifEmpty { null } }

    private fun selAttr(doc: org.jsoup.nodes.Document, attr: String, vararg sels: String): String? =
        sels.firstNotNullOfOrNull { doc.selectFirst(it)?.attr(attr)?.trim()?.ifEmpty { null } }

    private fun absUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$baseUrl$url"
        return "${baseUrl}/$url"
    }
}
