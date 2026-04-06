package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.HttpEngine
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * BaoziMH (包子漫画) — HTML scraping with multi-mirror fallback.
 *
 * Images are absolute CDN URLs (s{n}.bzcdn.net), no decryption needed.
 * Chapters are split across sub-pages; the reader follows pagination links.
 */
class BaoziNativeSource(client: OkHttpClient) : MangaSource {

    override val name    = "包子漫画"
    override val baseUrl = "https://www.baozimh.com"

    private val http = HttpEngine(
        client  = client,
        mirrors = listOf("https://www.webmota.com", "https://cn.baozimh.com",
                         "https://www.baozimh.com", "https://cn.bzmgcn.com"),
        headers = mapOf(
            "User-Agent" to UA,
            "Referer"    to "https://www.baozimh.com/"
        )
    )

    // ─── Popular / Search ────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching { parseMangaCards(http.fetch("/")) }
    }

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            parseMangaCards(http.fetch("/search?q=$q"))
        }
    }

    private fun parseMangaCards(html: String): List<Manga> {
        val doc = Jsoup.parse(html)
        return doc.select(".comics-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
            val href = link.attr("href").ifEmpty { return@mapNotNull null }
            val title = card.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").ifEmpty { return@mapNotNull null }
            val cover = card.selectFirst("amp-img")?.attr("src")
                ?: card.selectFirst("img")?.attr("src") ?: ""
            val slug = href.removePrefix("/comic/").trimEnd('/')
            if (slug.isBlank() || slug.contains("/")) return@mapNotNull null
            Manga(title = title, coverUrl = cover, detailUrl = "/comic/$slug", sourceName = name)
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.parse(http.fetch(detailUrl))
            val title  = sel(doc, ".comics-detail__title", "h1") ?: "未知标题"
            val cover  = selAttr(doc, "src", ".de-info__box amp-img", ".comics-detail amp-img") ?: ""
            val author = sel(doc, ".comics-detail__author", "h2") ?: ""
            val desc   = sel(doc, "p.comics-detail__desc", "p[class*=desc]") ?: ""
            val status = sel(doc, ".comics-detail__info span") ?: ""
            val slug   = detailUrl.removePrefix("/comic/").substringBefore("?").trimEnd('/')

            val chapters = doc.select("a.comics-chapters__item").ifEmpty {
                doc.select("a[href*=page_direct]")
            }.mapIndexed { i, el ->
                val href = el.attr("href")
                val s = SECTION_RE.find(href)?.groupValues?.get(1) ?: "0"
                val c = CHAPTER_RE.find(href)?.groupValues?.get(1) ?: "$i"
                MangaChapter(
                    name = el.text().trim().ifEmpty { "第 ${i + 1} 章" },
                    url  = "/comic/chapter/$slug/${s}_${c}.html"
                )
            }

            MangaDetail(url = detailUrl, title = title, coverUrl = cover,
                        author = author, description = desc, status = status, chapters = chapters)
        }
    }

    // ─── Chapter Images ──────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val allImages = mutableListOf<String>()
            var currentUrl = chapterUrl
            val visited = mutableSetOf<String>()

            while (currentUrl !in visited && visited.size < 30) {
                visited.add(currentUrl)
                val doc = Jsoup.parse(http.fetch(currentUrl))

                doc.select("amp-img[src]").map { it.attr("src") }
                    .filter { it.startsWith("http") && "/scomic/" in it }
                    .let { allImages.addAll(it) }

                currentUrl = doc.select("a").firstOrNull { el ->
                    val t = el.text()
                    "下一頁" in t || "下一页" in t || "點擊進入下一頁" in t || "点击进入下一页" in t
                }?.attr("href")?.let { href ->
                    if (href.startsWith("http")) {
                        val p = href.substringAfter("://").substringAfter("/", "")
                        if (p.isNotEmpty()) "/$p" else null
                    } else href
                } ?: break
            }

            if (allImages.isEmpty()) error("未找到章节图片 — URL: $chapterUrl")
            allImages
        }
    }

    override fun getHeaders(): Map<String, String> = http.mirrors.first().let {
        mapOf("User-Agent" to UA, "Referer" to "https://www.baozimh.com/")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun sel(doc: Document, vararg sels: String): String? =
        sels.firstNotNullOfOrNull { doc.selectFirst(it)?.text()?.trim()?.ifEmpty { null } }

    private fun selAttr(doc: Document, attr: String, vararg sels: String): String? =
        sels.firstNotNullOfOrNull { doc.selectFirst(it)?.attr(attr)?.trim()?.ifEmpty { null } }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private val SECTION_RE = Regex("section_slot=(\\d+)")
        private val CHAPTER_RE = Regex("chapter_slot=(\\d+)")
    }
}
