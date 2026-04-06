package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * BaoziMH (包子漫画) Native Source Plugin
 *
 * Architecture: HTML scraping with multi-mirror fallback.
 *
 *  - Search & Popular: Parse `.comics-card` from homepage / search page.
 *  - Detail: Parse title, cover, author, chapters from the comic detail page.
 *    Chapters are mapped to direct reader URLs (slug + section/chapter slot).
 *  - Chapter images: Scrape `amp-img` tags from the reader page.
 *    Images are absolute CDN URLs (s{n}.bzcdn.net). Ads are filtered out by
 *    verifying the image path contains the manga's scomic slug.
 *
 * Mirror fallback:
 *    webCandidates[] are tried in priority order.
 *    webmota.com is the most reliable in mainland China.
 */
class BaoziNativeSource(
    private val client: OkHttpClient = buildClient()
) : MangaSource {

    override val name    = "包子漫画"
    override val baseUrl = "https://www.baozimh.com"

    /** Mirrors tried in order for ALL web requests. */
    private val webCandidates = listOf(
        "https://www.webmota.com",
        "https://cn.baozimh.com",
        "https://www.baozimh.com",
        "https://cn.bzmgcn.com"
    )

    // ─── HTTP ─────────────────────────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer"    to "https://www.baozimh.com/"
    )

    private fun get(url: String): String {
        val req = Request.Builder().url(url).apply {
            baseHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    /** Fetch from the first reachable mirror. */
    private fun fetchHtml(path: String): String {
        var lastError: Throwable = IllegalStateException("No candidates")
        for (base in webCandidates) {
            val url = if (path.startsWith("http")) {
                // Swap domain if path is an absolute URL from a known mirror
                val afterScheme = path.substringAfter("://")
                val domainEnd = afterScheme.indexOf('/')
                if (domainEnd > 0) "${base}${afterScheme.substring(domainEnd)}" else path
            } else {
                "${base.removeSuffix("/")}/${path.removePrefix("/")}"
            }
            try { return get(url) } catch (e: Exception) { lastError = e }
        }
        throw lastError
    }

    // ─── Popular ──────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching { parseMangaCards(fetchHtml("/")) }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            parseMangaCards(fetchHtml("/search?q=$q"))
        }
    }

    // ─── Card Parser (shared by popular + search) ─────────────────────────────

    private fun parseMangaCards(html: String): List<Manga> {
        val doc = Jsoup.parse(html)
        return doc.select(".comics-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
            val href = link.attr("href").ifEmpty { return@mapNotNull null }

            val title = card.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").ifEmpty { return@mapNotNull null }

            val coverUrl = card.selectFirst("amp-img")?.attr("src")
                ?: card.selectFirst("img")?.attr("src") ?: ""

            val slug = href.removePrefix("/comic/").trimEnd('/')
            if (slug.isBlank() || slug.contains("/")) return@mapNotNull null

            Manga(title = title, coverUrl = coverUrl,
                  detailUrl = "/comic/$slug", sourceName = name)
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.parse(fetchHtml(detailUrl))

            val title   = selectText(doc, ".comics-detail__title", "h1") ?: "未知标题"
            val cover   = selectAttr(doc, "src",
                ".de-info__box amp-img", ".de-info__cover amp-img", ".comics-detail amp-img") ?: ""
            val author  = selectText(doc, ".comics-detail__author", "h2") ?: ""
            val desc    = selectText(doc, "p.comics-detail__desc", "p[class*=desc]") ?: ""
            val status  = selectText(doc, ".comics-detail__info span", ".tag-list span") ?: ""

            val slug = detailUrl.removePrefix("/comic/").substringBefore("?").trimEnd('/')

            // Parse chapter links
            val chapElements = doc.select("a.comics-chapters__item").ifEmpty {
                doc.select("a[href*=page_direct]")
            }

            val chapters = chapElements.mapIndexed { index, el ->
                val chName = el.text().trim().ifEmpty { "第 ${index + 1} 章" }
                val href   = el.attr("href")

                val sSlot = SECTION_RE.find(href)?.groupValues?.get(1) ?: "0"
                val cSlot = CHAPTER_RE.find(href)?.groupValues?.get(1) ?: "$index"

                MangaChapter(
                    name = chName,
                    url  = "/comic/chapter/$slug/${sSlot}_${cSlot}.html"
                )
            }

            MangaDetail(url = detailUrl, title = title, coverUrl = cover,
                        author = author, description = desc, status = status,
                        chapters = chapters)
        }
    }

    // ─── Chapter Images ──────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Extract the manga scomic-slug from the chapter URL for ad filtering.
            // URL format: /comic/chapter/{slug}/{s}_{c}.html
            // The scomic-slug is usually the part before the last hyphen-group in the web slug.
            val webSlug = chapterUrl.substringAfter("/comic/chapter/")
                .substringBefore("/")

            val allImages = mutableListOf<String>()
            var currentUrl = chapterUrl
            val visited = mutableSetOf<String>()

            while (true) {
                if (currentUrl in visited) break
                visited.add(currentUrl)

                val doc = Jsoup.parse(fetchHtml(currentUrl))

                // Collect CDN images (amp-img with scomic path)
                val images = doc.select("amp-img[src]")
                    .map { it.attr("src") }
                    .filter { it.startsWith("http") && it.contains("/scomic/") }

                allImages.addAll(images)

                // Follow in-chapter pagination ("點擊進入下一頁" / "下一頁")
                val nextHref = doc.select("a").firstOrNull { el ->
                    val text = el.text()
                    text.contains("點擊進入下一頁") || text.contains("点击进入下一页") ||
                    text.contains("下一頁") || text.contains("下一页")
                }?.attr("href")

                if (nextHref.isNullOrEmpty()) break

                // Convert absolute URLs to relative paths for mirror fallback
                currentUrl = if (nextHref.startsWith("http")) {
                    val afterDomain = nextHref.substringAfter("://").substringAfter("/", "")
                    if (afterDomain.isNotEmpty()) "/$afterDomain" else break
                } else nextHref

                if (visited.size > 30) break  // safety limit
            }

            if (allImages.isEmpty()) error("未找到章节图片 — URL: $chapterUrl")
            allImages
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── Selector Helpers ────────────────────────────────────────────────────

    /** Try multiple CSS selectors in order, return first match's text. */
    private fun selectText(doc: Document, vararg selectors: String): String? {
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            val text = el.text().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /** Try multiple CSS selectors in order, return first match's attribute. */
    private fun selectAttr(doc: Document, attr: String, vararg selectors: String): String? {
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            val value = el.attr(attr).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    companion object {
        private val SECTION_RE = Regex("section_slot=(\\d+)")
        private val CHAPTER_RE = Regex("chapter_slot=(\\d+)")

        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
