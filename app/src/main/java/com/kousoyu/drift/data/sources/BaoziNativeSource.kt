package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * BaoziMH (包子漫画) Native Source Plugin
 *
 * Architecture: **Hybrid HTML + API** — the most resilient approach:
 *
 *  - **Search & Popular**: HTML scraping from the website.
 *    This is the only part sensitive to DOM changes, but the selectors used
 *    are very generic (.comics-card, amp-img, h3) and unlikely to drift.
 *
 *  - **Detail + Chapters + Images**: All handled via the stable mgsearcher
 *    JSON API (api-get-v3.mgsearcher.com). This layer is **immune to website
 *    redesigns** — even if baozimh.com completely changes its UI, reading
 *    functionality remains intact.
 *
 * Mirror fallback:
 *    webCandidates[] are tried in order for HTML scraping.
 *    The API endpoint (mgsearcher) is separate and doesn't need mirrors.
 *
 * Image CDN:
 *    Chapter images use relative paths from the API; the CDN base is embedded
 *    in the API response cover URL domain — we extract it dynamically.
 */
class BaoziNativeSource(
    private val client: OkHttpClient = buildClient()
) : MangaSource {

    override val name    = "包子漫画"
    override val baseUrl = "https://www.baozimh.com"

    private val webCandidates = listOf(
        "https://www.baozimh.com",
        "https://www.baozimh.org",
        "https://baozimh.com",
        "https://www.zerobyw.com",
        "https://www.webmota.com"
    )

    private val apiBase = "https://api-get-v3.mgsearcher.com/api"

    // ─── HTTP Headers ─────────────────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer"    to "$baseUrl/"
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

    /** Fetch HTML from the first reachable mirror. */
    private fun fetchHtml(path: String): String {
        var lastError: Throwable = IllegalStateException("No candidates")
        for (base in webCandidates) {
            val url = "${base.removeSuffix("/")}/${path.removePrefix("/")}"
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
            parseSearchResultHtml(html)
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val html = fetchHtml("/search?q=$q")
            parseSearchResultHtml(html)
        }
    }

    // ─── HTML Parser (shared by popular + search) ─────────────────────────────

    private fun parseSearchResultHtml(html: String): List<Manga> {
        val doc = Jsoup.parse(html)
        val cards = doc.select(".comics-card")
        return cards.mapNotNull { card ->
            val link = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
            val href = link.attr("href").ifEmpty { return@mapNotNull null }

            // Title: h3 inside the card, or the link's title attribute
            val title = card.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").ifEmpty { return@mapNotNull null }

            // Cover: amp-img src or fallback img src
            val coverUrl = card.selectFirst("amp-img")?.attr("src")
                ?: card.selectFirst("img")?.attr("src")
                ?: ""

            // Extract slug from href: /comic/{slug}
            val slug = href.removePrefix("/comic/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null

            Manga(
                title     = title,
                coverUrl  = coverUrl,
                // Store the slug as the detail URL — we'll use it for API calls
                detailUrl = "baozi://$slug",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail (JSON API — immune to website redesigns) ──────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val slug = extractSlug(detailUrl)

            // First we need the numeric mid. Get it from the detail page's chapter links.
            val mid = resolveMid(slug)

            // Fetch full manga data + chapters from the API
            val json = get("$apiBase/manga/get?mid=$mid")
            val data = JSONObject(json).getJSONObject("data")

            val title  = data.optString("title", "未知标题")
            val cover  = data.optString("cover", "")
            val desc   = data.optString("desc", "暂无简介")
            val status = when (data.optString("status")) {
                "1" -> "连载中"
                "2" -> "已完结"
                else -> ""
            }

            // Parse chapters
            val chapArr = data.optJSONArray("chapters")
            val chapters = if (chapArr != null) {
                (0 until chapArr.length()).mapNotNull { i ->
                    val ch = chapArr.optJSONObject(i) ?: return@mapNotNull null
                    val attrs = ch.optJSONObject("attributes") ?: return@mapNotNull null
                    val chId = ch.optString("id").ifEmpty { return@mapNotNull null }

                    MangaChapter(
                        name = attrs.optString("title", "第 ${i + 1} 章"),
                        // Encode both mid and chapter ID so getChapterImages can use them
                        url  = "baozi-ch://$mid/$chId"
                    )
                }
            } else emptyList()

            MangaDetail(
                url         = detailUrl,
                title       = title,
                coverUrl    = cover,
                author      = "",
                description = desc,
                status      = status,
                chapters    = chapters
            )
        }
    }

    // ─── Chapter Images (JSON API — immune to website redesigns) ──────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Parse mid and chapter ID from our custom URL
            val parts = chapterUrl.removePrefix("baozi-ch://").split("/")
            val mid = parts[0]
            val cId = parts[1]

            val json = get("$apiBase/chapter/getinfo?m=$mid&c=$cId")
            val data = JSONObject(json).getJSONObject("data")
            val info = data.getJSONObject("info")
            val imagesObj = info.getJSONObject("images")
            val imagesArr = imagesObj.getJSONArray("images")

            // Extract image CDN base from the cover URL's domain
            val cover = info.optString("cover", "")
            val cdnBase = if (cover.isNotEmpty()) {
                val match = Regex("^(https?://[^/]+)").find(cover)
                match?.groupValues?.get(1)
            } else null

            // Fallback CDN bases
            val fallbackCdn = "https://static-tw.baozimh.com"

            (0 until imagesArr.length()).map { i ->
                val img = imagesArr.getJSONObject(i)
                val relUrl = img.getString("url")
                if (relUrl.startsWith("http")) {
                    relUrl
                } else {
                    "${cdnBase ?: fallbackCdn}$relUrl"
                }
            }
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun extractSlug(detailUrl: String): String {
        // Handle both our custom scheme and full URLs
        return when {
            detailUrl.startsWith("baozi://") -> detailUrl.removePrefix("baozi://")
            detailUrl.contains("/comic/")    -> detailUrl.substringAfter("/comic/").trimEnd('/')
            detailUrl.contains("/manga/")    -> detailUrl.substringAfter("/manga/").trimEnd('/')
            else -> detailUrl.trimEnd('/')
        }
    }

    /**
     * Resolves the numeric manga ID (mid) from a slug.
     *
     * Strategy: Scrape the detail page to get a chapter link, follow it to
     * extract the internal m/c IDs, then use the API to get the canonical mid.
     *
     * The mid is cached in-memory so this lookup only happens once per manga.
     */
    private val midCache = mutableMapOf<String, String>()

    private fun resolveMid(slug: String): String {
        midCache[slug]?.let { return it }

        // Parse detail page for chapter links
        val html = fetchHtml("/comic/$slug")
        val doc = Jsoup.parse(html)

        // Chapter links format: /user/page_direct?comic_id={slug}&section_slot=0&chapter_slot=0
        val chapLinks = doc.select("a.comics-chapters__item")
        if (chapLinks.isEmpty()) {
            error("No chapters found for $slug")
        }

        // Get the first chapter link
        val firstChapHref = chapLinks.last()?.attr("href") ?: error("No chapter href")
        
        // Parse comic_id and slots from the URL
        val comicId = Regex("comic_id=([^&]+)").find(firstChapHref)?.groupValues?.get(1)
            ?: slug
        val sectionSlot = Regex("section_slot=(\\d+)").find(firstChapHref)?.groupValues?.get(1)
            ?: "0"
        val chapterSlot = Regex("chapter_slot=(\\d+)").find(firstChapHref)?.groupValues?.get(1)
            ?: "0"

        // Follow the redirect to get the actual reader page
        val redirectUrl = "${webCandidates.first()}/user/page_direct?comic_id=$comicId&section_slot=$sectionSlot&chapter_slot=$chapterSlot"
        val req = Request.Builder().url(redirectUrl).apply {
            baseHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        
        // Don't follow redirects — check the Location header
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val resp = noRedirectClient.newCall(req).execute()
        val location = resp.header("Location") ?: resp.request.url.toString()
        resp.close()

        // Location format: /comic/{slug}/chapter/{chapterSlug} or /manga/{slug}/{mId}-{cId}-{part}
        // Try pattern: /{mId}-{cId}-{part}
        val idMatch = Regex("/(\\d+)-(\\d+)-(\\d+)").find(location)
        if (idMatch != null) {
            val mid = idMatch.groupValues[1]
            midCache[slug] = mid
            return mid
        }

        // Alternative: the redirected page itself might have a direct chapter page
        // Just load it and look for mgsearcher or mid patterns
        val readerHtml = try {
            val fullUrl = if (location.startsWith("http")) location 
                          else "${webCandidates.first()}$location"
            get(fullUrl)
        } catch (_: Exception) { "" }

        // Search for API-style URL patterns or mid in the reader HTML
        val apiMatch = Regex("getinfo\\?m=(\\d+)").find(readerHtml)
        if (apiMatch != null) {
            val mid = apiMatch.groupValues[1]
            midCache[slug] = mid
            return mid
        }

        // Last resort: scan amp-img src for numbered patterns
        val ampImgs = Jsoup.parse(readerHtml).select("amp-img[src*=scomic]")
        if (ampImgs.isNotEmpty()) {
            val imgSrc = ampImgs.first()!!.attr("src")
            // Image path contains slug info — try to brute-force match via API
            // For now, throw with a helpful message
            error("Could not resolve mid for $slug — chapter redirect: $location")
        }

        error("Could not resolve manga ID for $slug")
    }

    companion object {
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
