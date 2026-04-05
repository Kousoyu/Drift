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
import java.util.concurrent.TimeUnit

/**
 * BaoziMH (包子漫画) Native Source Plugin
 *
 * Architecture: **HTML scraping with stable selectors + mirror fallback**.
 *
 * Key design:
 *  - Search & Popular: HTML from the homepage / search page.
 *  - Detail: HTML from the comic detail page. Chapters are extracted as
 *    direct reader-page URLs (slug + section_slot/chapter_slot → reader path).
 *  - Chapter images: Direct scraping of `amp-img` from the reader page.
 *    Images are absolute CDN URLs (s1.bzcdn.net) — no extra resolving needed.
 *
 * Mirror fallback:
 *    webCandidates[] are tried in order. The reader page is accessible from
 *    baozimh.com, webmota.com, and cn.bzmgcn.com. baozimh.org uses /manga/
 *    instead of /comic/ and is excluded.
 *
 * Unlike the DynamicMangaSource approach, this plugin knows the exact URL
 * patterns and structures, making it more resilient to minor DOM changes.
 */
class BaoziNativeSource(
    private val client: OkHttpClient = buildClient()
) : MangaSource {

    override val name    = "包子漫画"
    override val baseUrl = "https://www.baozimh.com"

    /** Mirrors tried in order for ALL web requests. */
    private val webCandidates = listOf(
        "https://www.webmota.com",      // most reliable in mainland China
        "https://cn.baozimh.com",
        "https://www.baozimh.com",
        "https://cn.bzmgcn.com"
    )

    // ─── HTTP Headers ─────────────────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer"    to "https://www.baozimh.com/"
    )

    // ─── HTTP Helpers ─────────────────────────────────────────────────────────

    private fun get(url: String): String {
        val req = Request.Builder().url(url).apply {
            baseHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    /**
     * Fetch from the first reachable mirror.
     * For absolute URLs, swaps the domain if it matches a known candidate.
     */
    private fun fetchHtml(path: String): String {
        var lastError: Throwable = IllegalStateException("No candidates")
        for (base in webCandidates) {
            val url = if (path.startsWith("http")) {
                val matched = webCandidates.find { path.startsWith(it) }
                if (matched != null) path.replaceFirst(matched, base)
                else path
            } else {
                "${base.removeSuffix("/")}/${path.removePrefix("/")}"
            }
            try {
                return get(url)
            } catch (e: Exception) { lastError = e }
        }
        throw lastError
    }

    // ─── Popular ──────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val html = fetchHtml("/")
            parseMangaCards(html)
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val html = fetchHtml("/search?q=$q")
            parseMangaCards(html)
        }
    }

    // ─── HTML Card Parser (shared by popular + search) ────────────────────────

    private fun parseMangaCards(html: String): List<Manga> {
        val doc = Jsoup.parse(html)
        val cards = doc.select(".comics-card")
        return cards.mapNotNull { card ->
            val link = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
            val href = link.attr("href").ifEmpty { return@mapNotNull null }

            val title = card.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").ifEmpty { return@mapNotNull null }

            val coverUrl = card.selectFirst("amp-img")?.attr("src")
                ?: card.selectFirst("img")?.attr("src")
                ?: ""

            // Extract slug from: /comic/{slug}
            val slug = href.removePrefix("/comic/").trimEnd('/')
            if (slug.isBlank() || slug.contains("/")) return@mapNotNull null

            Manga(
                title      = title,
                coverUrl   = coverUrl,
                detailUrl  = "/comic/$slug",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val html = fetchHtml(detailUrl)
            val doc = Jsoup.parse(html)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: "未知标题"
            val coverUrl = doc.selectFirst(".de-info__box amp-img")?.attr("src")
                ?: doc.selectFirst("amp-img")?.attr("src")
                ?: ""
            val author = doc.selectFirst("h2")?.text()?.trim() ?: ""
            val desc = doc.selectFirst("p.comics-detail__desc")?.text()?.trim()
                ?: doc.selectFirst("p[class*=desc]")?.text()?.trim()
                ?: ""
            val status = doc.selectFirst(".comics-detail__info span")?.text()?.trim() ?: ""

            // Extract slug from detailUrl
            val slug = detailUrl.removePrefix("/comic/")
                .substringBefore("?").trimEnd('/')

            // Parse chapter links
            // Format: /user/page_direct?comic_id={slug}&section_slot={s}&chapter_slot={c}
            val chapElements = doc.select("a.comics-chapters__item")
            val chapters = chapElements.mapIndexed { index, el ->
                val chapterName = el.text().trim().ifEmpty { "第 ${index + 1} 章" }
                val href = el.attr("href")

                // Extract section_slot and chapter_slot
                val sectionSlot = Regex("section_slot=(\\d+)").find(href)?.groupValues?.get(1) ?: "0"
                val chapterSlot = Regex("chapter_slot=(\\d+)").find(href)?.groupValues?.get(1) ?: "$index"

                // Build direct reader URL: /comic/chapter/{slug}/{section}_{chapter}.html
                MangaChapter(
                    name = chapterName,
                    url  = "/comic/chapter/$slug/${sectionSlot}_${chapterSlot}.html"
                )
            }

            MangaDetail(
                url         = detailUrl,
                title       = title,
                coverUrl    = coverUrl,
                author      = author,
                description = desc,
                status      = status,
                chapters    = chapters
            )
        }
    }

    // ─── Chapter Images ──────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val allImages = mutableListOf<String>()
            var currentUrl = chapterUrl
            val visited = mutableSetOf<String>()

            // Follow pagination (多页章节): baozi splits some chapters across sub-pages
            while (true) {
                if (currentUrl in visited) break
                visited.add(currentUrl)

                val html = fetchHtml(currentUrl)
                val doc = Jsoup.parse(html)

                // Collect images: amp-img with CDN src
                val images = doc.select("amp-img[src]")
                    .map { it.attr("src") }
                    .filter { it.contains("/scomic/") || it.contains("/comic/") }
                    .filter { it.startsWith("http") }

                allImages.addAll(images)

                // Check for "next page" within the same chapter
                // The "下一页" link will be to: .../slug/section_chapter_page.html
                // or: section_chapter.html (single page)
                val nextLink = doc.select("a")
                    .filter { it.text().contains("下一頁") || it.text().contains("下一页") }
                    .firstOrNull()
                    ?.attr("href")

                if (nextLink.isNullOrEmpty()) break

                // Resolve relative URL
                currentUrl = if (nextLink.startsWith("http")) {
                    // Convert to relative path for mirror fallback
                    val path = Regex("https?://[^/]+(.*)").find(nextLink)?.groupValues?.get(1) ?: break
                    path
                } else {
                    nextLink
                }

                // Safety: stop if we've followed too many pages (prevent infinite loop)
                if (visited.size > 20) break
            }

            if (allImages.isEmpty()) {
                error("未找到章节图片 — URL: $chapterUrl")
            }

            allImages
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    companion object {
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
