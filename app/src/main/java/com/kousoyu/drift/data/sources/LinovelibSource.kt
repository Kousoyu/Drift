package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 哔哩轻小说 — Pure OkHttp + Jsoup. No WebView. No Cloudflare bypass.
 *
 * All endpoints tested CF-free (2026-04-08):
 *   ✅ Homepage, Rankings, Catalog, Chapter Content — all pure HTTP.
 *
 * Key: Chapters are paginated (11067.html → 11067_2.html → …).
 *      We fetch ALL pages and merge them.
 */
class LinovelibSource(
    private val client: OkHttpClient
) : NovelSource {

    override val name = "哔哩轻小说"
    override val baseUrl: String get() = "https://${resolvedDomain ?: FALLBACK}"

    private var resolvedDomain: String? = null
    private val FALLBACK = "www.bilinovel.com"

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ─── Init domain ────────────────────────────────────────────────────────

    private suspend fun ensureDomain() {
        if (resolvedDomain == null) {
            resolvedDomain = DomainResolver.get()
        }
    }

    // ─── HTTP ───────────────────────────────────────────────────────────────

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        ensureDomain()
        val url = if (path.startsWith("http")) path
                  else "https://$resolvedDomain/${path.trimStart('/')}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "https://$resolvedDomain/")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response")
        }
    }

    // ─── Popular (排行榜) ───────────────────────────────────────────────────

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

    // ─── Detail + Catalog ───────────────────────────────────────────────────

    override suspend fun getNovelDetail(detailUrl: String): Result<NovelDetail> = runCatching {
        val html = fetch(detailUrl)
        val doc = Jsoup.parse(html, baseUrl)

        val title  = sel(doc, "h1", "h2.book-title", ".book-title") ?: "未知标题"
        val author = sel(doc, ".book-rand-a a", ".author a") ?: ""
        val cover  = selAttr(doc, "src", ".book-img img", ".book-cover img")
            ?: selAttr(doc, "data-src", ".book-img img", ".book-cover img")
            ?: selAttr(doc, "content", "meta[property=og:image]") ?: ""
        val desc   = sel(doc, ".book-dec p", ".book-info-detail .book-dec") ?: ""
        val status = sel(doc, ".book-label span", "span.state") ?: ""

        val novelId = detailUrl.substringAfterLast("/novel/").substringBefore(".html")
        val catalogHtml = fetch("/novel/$novelId/catalog")
        val catalogDoc = Jsoup.parse(catalogHtml, baseUrl)
        val volumes = parseCatalog(catalogDoc, title)

        NovelDetail(
            url = detailUrl, title = title, author = author,
            coverUrl = absUrl(cover), description = desc,
            status = status, volumes = volumes
        )
    }

    // ─── Chapter Content (multi-page merge) ─────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): Result<String> = runCatching {
        val parts = mutableListOf<String>()
        var currentUrl = chapterUrl
        fun extractBase(url: String): String =
            url.substringAfterLast("/").substringBefore(".html").substringBefore("_")

        val chapterBase = extractBase(currentUrl)
        val maxPages = 30

        for (i in 0 until maxPages) {
            // Small delay between pages to avoid rate limiting (error 1015)
            if (i > 0) kotlinx.coroutines.delay(200)

            val html = try {
                fetch(currentUrl)
            } catch (e: Exception) {
                // If a sub-page fails, keep what we have rather than losing everything
                if (parts.isNotEmpty()) break else throw e
            }

            val doc = Jsoup.parse(html, baseUrl)

            val content = doc.selectFirst("#acontent, #TextContent, .read-content, .chapter-content")
            if (content != null) {
                // Clean ads/scripts/navigation junk
                content.select("script, .google-auto-placed, .adsbygoogle, ins, .tp-box, style, .cgo, .ca1, .ca2, .chapter-nav, .mlfy_main_top, .mlfy_main_bottom").remove()

                // Extract paragraphs
                val paragraphs = mutableListOf<String>()
                for (node in content.children()) {
                    val text = node.text().trim()
                    if (text.isNotEmpty()) paragraphs.add(text)
                }
                // Fallback: br-separated
                if (paragraphs.isEmpty()) {
                    content.select("br").append("|||BR|||")
                    paragraphs.addAll(
                        content.text().split("|||BR|||")
                            .map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
                // Fallback: raw text
                if (paragraphs.isEmpty()) {
                    val raw = content.text().trim()
                    if (raw.isNotEmpty()) paragraphs.add(raw)
                }

                parts.addAll(paragraphs)
            }

            // Find next page from ReadParams
            val nextUrl = Regex("url_next:'([^']+)'").find(html)?.groupValues?.get(1) ?: break

            // If next URL points to a different chapter → we've read all pages
            if (extractBase(nextUrl) != chapterBase) break

            currentUrl = nextUrl
        }

        // Clean each paragraph individually, then join
        parts.map { p ->
            p.replace(Regex("【如需繼續閱讀[^】]*】?"), "")
             .replace(Regex("如需繼續閱讀.*"), "")
             .replace(Regex("【?如需继续阅读[^】]*】?"), "")
             .replace(Regex("\\[Chrome瀏覽器\\].*"), "")
             .replace(Regex("www\\.bilino?vel\\.com"), "")
             .replace(Regex("^[【】\\s]+$"), "")  // lone brackets
             .trim()
        }.filter { it.isNotEmpty() }
         .joinToString("\n\n")
         .replace(Regex("\n{3,}"), "\n\n")
         .trim()
    }

    override fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "$baseUrl/"
    )

    // ─── Parse Logic ────────────────────────────────────────────────────────

    private fun parseNovelList(html: String): List<NovelItem> {
        val doc = Jsoup.parse(html, baseUrl)

        val bookItems = doc.select(".book-li, .rank-book-list li, .book-layout, .book-item")
        if (bookItems.isNotEmpty()) {
            return bookItems.mapNotNull { el ->
                val linkEl = el.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
                val title = el.selectFirst("h4, h3, h2, .book-title")?.text()?.trim()
                    ?: linkEl.text().trim()
                if (title.isBlank() || title.length < 2) return@mapNotNull null

                val href = absUrl(linkEl.attr("href"))
                if (!href.contains("/novel/")) return@mapNotNull null

                // Cover: try data-src first (lazy-loaded), then src
                val cover = el.selectFirst("img")?.let { img ->
                    val dataSrc = img.attr("data-src").trim()
                    val src = img.attr("src").trim()
                    absUrl(dataSrc.ifEmpty { src })
                } ?: ""
                val author = el.selectFirst(".author a, .book-author")?.text()?.trim() ?: ""

                NovelItem(title = title, author = author, coverUrl = cover,
                    detailUrl = href, sourceName = name)
            }.distinctBy { it.detailUrl }
        }

        // Fallback
        return doc.select("a[href*=/novel/]").mapNotNull { a ->
            val href = absUrl(a.attr("href"))
            if (!href.matches(Regex(".*/novel/\\d+\\.html$"))) return@mapNotNull null
            val title = a.text().trim()
            if (title.length < 2) return@mapNotNull null
            val skip = setOf("首页", "分类", "排行", "全本", "文库", "登录", "注册", "立即阅读", "开始阅读", "更多")
            if (title in skip) return@mapNotNull null
            NovelItem(title = title, author = "", coverUrl = "", detailUrl = href, sourceName = name)
        }.distinctBy { it.detailUrl }
    }

    private fun parseCatalog(doc: org.jsoup.nodes.Document, fallbackTitle: String): List<NovelVolume> {
        val volumes = mutableListOf<NovelVolume>()

        val chapterLists = doc.select(".chapter-list, .volume-list, ol.chapter-bar-list")
        if (chapterLists.isNotEmpty()) {
            chapterLists.forEach { volEl ->
                val volName = volEl.previousElementSibling()?.text()?.trim()
                    ?: volEl.attr("data-volume-name").ifEmpty { null }
                    ?: "未知卷"
                val chapters = volEl.select("li a, a.chapter-li-a").mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank() || href.startsWith("javascript")) return@mapNotNull null
                    NovelChapter(name = a.text().trim(), url = absUrl(href))
                }
                if (chapters.isNotEmpty()) {
                    volumes.add(NovelVolume(name = volName, chapters = chapters))
                }
            }
        }

        if (volumes.isEmpty()) {
            val allChapters = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains("/novel/") || href.endsWith("/catalog") || !href.endsWith(".html")) return@mapNotNull null
                if (href.startsWith("javascript")) return@mapNotNull null
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
        return "$baseUrl/$url"
    }
}
