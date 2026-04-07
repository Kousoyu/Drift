package com.kousoyu.drift.data.sources

import android.content.Context
import com.kousoyu.drift.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 哔哩轻小说 (linovelib.com) — Light Novel source.
 *
 * Uses CloudflareBypass for initial cookie acquisition,
 * then OkHttp + Jsoup for all subsequent requests.
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

    private val cfBypass = CloudflareBypass(context)

    // ─── Cookie-aware fetch ─────────────────────────────────────────────────

    /**
     * Fetch HTML with Cloudflare cookie.
     * First tries cached cookie; if that fails (403), re-solves via WebView.
     */
    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path
                  else "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

        // Try with cached cookies first
        val cachedCookies = cfBypass.getCachedCookies(baseUrl)
        if (cachedCookies != null) {
            try {
                val html = doFetch(url, cachedCookies)
                if (!isCloudflareBlock(html)) return@withContext html
            } catch (_: Exception) { /* cookie expired, re-solve */ }
        }

        // Re-solve Cloudflare via WebView
        val freshCookies = withContext(Dispatchers.Main) {
            cfBypass.getCookies(baseUrl)
        }
        doFetch(url, freshCookies)
    }

    private fun doFetch(url: String, cookies: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", CloudflareBypass.UA)
            .header("Referer", "$baseUrl/")
            .build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    private fun isCloudflareBlock(html: String): Boolean {
        return "Just a moment" in html || "请稍候" in html || "cf-challenge" in html
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

        val title  = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst(".book-title")?.text()?.trim() ?: "未知标题"
        val author = doc.selectFirst(".book-rand-a a")?.text()?.trim()
            ?: doc.select("span:contains(作者) + a, .author a").firstOrNull()?.text()?.trim() ?: ""
        val cover  = doc.selectFirst(".book-img img")?.let { absUrl(it.attr("src")) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val desc   = doc.selectFirst(".book-dec p, .book-info-detail .book-dec")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        val status = doc.selectFirst(".book-label span, span.state")?.text()?.trim() ?: ""

        // Fetch catalog page for volumes + chapters
        val novelId = detailUrl.substringAfterLast("/novel/").substringBefore(".html")
        val catalogHtml = fetch("/novel/$novelId/catalog")
        val catalogDoc = Jsoup.parse(catalogHtml, baseUrl)

        val volumes = mutableListOf<NovelVolume>()
        // linovelib catalog: volume headers in <h4> or <div class="chapter-bar">,
        // chapters in <li><a> within the same section
        val volumeElements = catalogDoc.select(".chapter-list")
        if (volumeElements.isNotEmpty()) {
            volumeElements.forEach { volEl ->
                val volName = volEl.previousElementSibling()?.text()?.trim()
                    ?: "未知卷"
                val chapters = volEl.select("li a").map { a ->
                    NovelChapter(
                        name = a.text().trim(),
                        url = absUrl(a.attr("href"))
                    )
                }
                if (chapters.isNotEmpty()) {
                    volumes.add(NovelVolume(name = volName, chapters = chapters))
                }
            }
        }

        // Fallback: flat chapter structure
        if (volumes.isEmpty()) {
            val allChapters = catalogDoc.select("a[href*=/novel/]").filter {
                it.attr("href").count { c -> c == '/' } >= 3
            }.map { a ->
                NovelChapter(name = a.text().trim(), url = absUrl(a.attr("href")))
            }.distinctBy { it.url }

            if (allChapters.isNotEmpty()) {
                volumes.add(NovelVolume(name = title, chapters = allChapters))
            }
        }

        NovelDetail(
            url = detailUrl,
            title = title,
            author = author,
            coverUrl = cover,
            description = desc,
            status = status,
            volumes = volumes
        )
    }

    // ─── Chapter Content ────────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): Result<String> = runCatching {
        val html = fetch(chapterUrl)
        val doc = Jsoup.parse(html, baseUrl)

        // linovelib chapter content is in #acontent or .read-content
        val content = doc.selectFirst("#acontent, .read-content, .chapter-content")
            ?: error("未找到章节内容")

        // Remove script tags and ads
        content.select("script, .google-auto-placed, .adsbygoogle, ins, .tp-box").remove()

        // Convert <br> and <p> to newlines for clean text
        content.select("br").append("\\n")
        content.select("p").prepend("\\n")

        content.text()
            .replace("\\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // ─── Headers ────────────────────────────────────────────────────────────

    override fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to CloudflareBypass.UA,
        "Referer" to "$baseUrl/"
    )

    // ─── Parse Helpers ──────────────────────────────────────────────────────

    private fun parseNovelList(html: String): List<NovelItem> {
        val doc = Jsoup.parse(html, baseUrl)

        // Try ranking page format first
        val items = doc.select(".book-li, .rank-list .book-layout, li[class*=book]")

        if (items.isNotEmpty()) {
            return items.mapNotNull { el ->
                val titleEl = el.selectFirst("h4 a, .book-title a, h2 a") ?: return@mapNotNull null
                val title = titleEl.text().trim().ifEmpty { return@mapNotNull null }
                val href = absUrl(titleEl.attr("href"))
                val cover = el.selectFirst("img")?.let { absUrl(it.attr("src").ifEmpty { it.attr("data-src") }) } ?: ""
                val author = el.selectFirst(".author, .book-author, span:contains(作者)")?.text()
                    ?.replace("作者：", "")?.trim() ?: ""
                val status = el.selectFirst(".book-label span, .status")?.text()?.trim() ?: ""
                val genre = el.selectFirst(".book-tags span, .tag")?.text()?.trim() ?: ""

                NovelItem(
                    title = title,
                    author = author,
                    coverUrl = cover,
                    detailUrl = href,
                    genre = genre,
                    status = status,
                    sourceName = name
                )
            }.distinctBy { it.detailUrl }
        }

        // Fallback: generic link extraction
        return doc.select("a[href*=/novel/]").mapNotNull { a ->
            val href = absUrl(a.attr("href"))
            if (!href.contains("/novel/") || href.endsWith("/catalog")) return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            // Skip navigation links
            if (title.length < 2 || title in listOf("首页", "分类", "排行", "全本", "文库")) return@mapNotNull null
            NovelItem(
                title = title,
                author = "",
                coverUrl = "",
                detailUrl = href,
                sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    private fun absUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }
}
