package com.kousoyu.drift.data.sources

import com.kousoyu.drift.data.HttpEngine
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import com.kousoyu.drift.utils.LZString
import com.kousoyu.drift.utils.Unpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * ManhuaGui (漫画柜) — HTML + LZString/JS unpacking for chapter images.
 *
 * Image URLs are encrypted in a packed JS block inside the chapter page.
 * The block is decompressed via LZString, then unpacked to extract JSON
 * containing file paths and CDN tokens.
 */
class ManhuaguiSource(client: OkHttpClient) : MangaSource {

    override val name    = "漫画柜 (Native)"
    override val baseUrl = "https://tw.manhuagui.com"

    private val http = HttpEngine(
        client  = client,
        mirrors = listOf("https://tw.manhuagui.com", "https://www.manhuagui.com"),
        headers = mapOf(
            "Referer"    to "https://tw.manhuagui.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    )

    private val imageServer = "https://i.hamreus.com"

    override fun getHeaders(): Map<String, String> = mapOf(
        "Referer"    to baseUrl,
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // ─── Popular / Search ────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.parse(http.fetch("/list/view_p1.html"))
            doc.select("ul#contList > li").mapNotNull { item ->
                val a = item.selectFirst("a.bcover") ?: return@mapNotNull null
                val img = a.selectFirst("img")
                Manga(
                    title    = a.attr("title").trim(),
                    coverUrl = img?.let { if (it.hasAttr("src")) it.attr("abs:src") else it.attr("abs:data-src") } ?: "",
                    detailUrl  = a.attr("href"),
                    sourceName = name
                )
            }
        }
    }

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.parse(http.fetch("/s/${query}_p1.html"))
            doc.select("div.book-result > ul > li").mapNotNull { item ->
                val detail = item.selectFirst("div.book-detail") ?: return@mapNotNull null
                val a = detail.selectFirst("dl > dt > a") ?: return@mapNotNull null
                Manga(
                    title      = a.attr("title").trim(),
                    coverUrl   = item.selectFirst("div.book-cover > a.bcover > img")?.attr("abs:src") ?: "",
                    detailUrl  = a.attr("href"),
                    sourceName = name
                )
            }
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = if (detailUrl.startsWith("http")) detailUrl else "$baseUrl$detailUrl"
            val doc = Jsoup.parse(http.fetch(fullUrl), fullUrl)

            // Decrypt hidden (R18) chapters if present
            doc.selectFirst("#__VIEWSTATE")?.`val`()?.let { encoded ->
                LZString.decompressFromBase64(encoded)?.let { decoded ->
                    doc.selectFirst("#erroraudit_show")?.replaceWith(Jsoup.parse(decoded, fullUrl))
                }
            }

            val chapters = mutableListOf<MangaChapter>()
            doc.select("[id^=chapter-list-]").forEach { section ->
                val pages = section.select("ul").apply { reverse() }
                pages.forEach { page ->
                    page.select("li > a.status0").forEach { a ->
                        chapters.add(MangaChapter(
                            name = a.attr("title").trim().ifEmpty { a.selectFirst("span")?.ownText() ?: "" },
                            url  = a.attr("href")
                        ))
                    }
                }
            }

            MangaDetail(
                url         = fullUrl,
                title       = doc.select("div.book-title > h1:nth-child(1)").text().trim(),
                coverUrl    = doc.select("p.hcover > img").attr("abs:src"),
                author      = doc.select("span:contains(漫画作者) > a, span:contains(漫畫作者) > a")
                                 .text().trim().replace(" ", ", "),
                description = doc.select("div#intro-all").text().trim(),
                status      = doc.selectFirst("div.book-detail > ul.detail-list > li.status > span > span")?.text() ?: "",
                chapters    = chapters
            )
        }
    }

    // ─── Chapter Images ──────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"
            val html = http.fetch(fullUrl)

            val packed = PACKED_RE.find(html)?.groupValues?.get(1)
                ?: error("Cannot find packed JS code")

            val imgCode = packed.replace(PACKED_CONTENT_RE) { match ->
                val decoded = LZString.decompressFromBase64(match.groupValues[1])
                "'$decoded'.split('|')"
            }

            val unpacked = Unpacker.unpack(imgCode.replace("\\'", "-"))
            val jsonStr  = BLOCK_RE.find(unpacked)?.groupValues?.get(0)
                ?: error("Cannot find decoded JSON arguments")

            val json = JSONObject(jsonStr)
            if (!json.has("files")) error("No 'files' in JSON: ${jsonStr.take(150)}...")

            val files = json.getJSONArray("files")
            val path  = json.getString("path")
            val slE   = json.optJSONObject("sl")?.optString("e") ?: ""
            val slM   = json.optJSONObject("sl")?.optString("m") ?: ""

            (0 until files.length()).map { i ->
                "$imageServer$path${files.getString(i)}?e=$slE&m=$slM"
            }
        }
    }

    companion object {
        private val PACKED_RE         = Regex("""window\[".*?"]\((\(.*\)\s*\{[\s\S]+\}\s*\(.*\))""")
        private val PACKED_CONTENT_RE = Regex("""['"]([0-9A-Za-z+/=]+)['"]\\[['"].*?['"]]\(['"].*?['"]\)""")
        private val BLOCK_RE          = Regex("""\{.*\}""")
    }
}
