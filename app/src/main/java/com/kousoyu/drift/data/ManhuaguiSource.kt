package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

/**
 * Scraper implementation for Manhuagui TW (漫画柜繁体)
 * Domain: tw.manhuagui.com
 *
 * HTML structure (verified from live site):
 *   Popular  : ul#contList (or ul in .book-list) → li → a.bk[href=/comic/xxxx/] + img
 *              BUT: images use CSS background-image loaded via JS → use fallback span[style]
 *   Search   : GET /s/{keyword}.html
 *   Detail   : /comic/{id}/  → standard HTML, chapters in #chapterList a
 *   Images   : Page source has a JS packer (p,a,c,k,e,d) that decodes to JSON with imgPath list.
 *              We decode the packer in-app without executing JS.
 */
class ManhuaguiSource : MangaSource {
    override val name = "漫画柜"
    // tw.manhuagui.com is blocked in mainland — use www which resolves correctly
    override val baseUrl = "https://www.manhuagui.com"

    private val domains = listOf(
        "https://www.manhuagui.com",
        "https://tw.manhuagui.com",
        "https://m.manhuagui.com"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(DriftDns)   // ← DoH bypass: avoids ISP DNS poisoning for manhuagui.com
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .header("Accept-Language", "zh-TW,zh;q=0.9")
                .header("Cookie", "isAdult=1")  // Bypass adult content prompt
                .build()
            chain.proceed(req)
        }
        .build()

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch("$baseUrl/rank/")
                parseMangaList(html)
            }
        }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val html = fetch("$baseUrl/s/$encoded.html")
                parseMangaList(html)
            }
        }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(detailUrl)
                val doc = Jsoup.parse(html)

                val title = doc.selectFirst("div.book-title h1, h1.book-title")?.text()?.trim()
                    ?: doc.selectFirst("h1")?.text()?.trim() ?: "未知标题"

                val cover = doc.selectFirst("p.hcover img, img#flagMH, div.book-cover img")
                    ?.attr("src")?.let {
                        if (it.startsWith("http")) it else "https:$it"
                    } ?: ""

                val author = doc.select("div.book-detail span.detail-list-row span a").firstOrNull()
                    ?.text()?.trim()
                    ?: doc.selectFirst(".tag-links a, .detail-list a")?.text()?.trim() ?: ""

                val desc = doc.selectFirst("#intro-cut, p#intro-cut, div.book-intro")
                    ?.text()?.trim() ?: "暂无简介"

                val status = doc.selectFirst("div.detail-list span.sl-c, span.book-status")
                    ?.text()?.trim() ?: "连载中"

                // Chapter list
                val chapters = doc.select("#chapterList a, ul.chapter-list a").mapNotNull { a ->
                    val href = a.attr("href")
                    val chName = a.text().trim()
                    if (href.isNotEmpty() && chName.isNotEmpty() && href.contains("/comic/")) {
                        MangaChapter(chName, if (href.startsWith("http")) href else "$baseUrl$href")
                    } else null
                }.distinctBy { it.url }

                MangaDetail(detailUrl, title, cover, author, desc, status, chapters)
            }
        }

    // ─── Chapter Images ───────────────────────────────────────────────────────
    //
    // Manhuagui chapters embed images in a packed JS block:
    //   <script type="text/javascript">window["..."]</script>
    //   eval(function(p,a,c,k,e,d){ ... }('...', ...))
    //
    // We unpack this using a pure-Kotlin reimplementation of the classic
    // Dean Edwards packer decoder, then regex-extract the imgPath JSON array.
    // No JS runtime or JVM Scripting engine is required.
    //
    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(chapterUrl)

                // Step 1: find the packed script block
                val packedPattern = Regex("""eval\(function\(p,a,c,k,e[,d]*\)\{.*?}\('(.+?)',(\d+),(\d+),'([^']+)'""", RegexOption.DOT_MATCHES_ALL)
                val match = packedPattern.find(html)
                    ?: error("漫画柜：未找到章节图片加密数据块")

                val packedStr = match.groupValues[1]
                val radix = match.groupValues[2].toInt()
                val count = match.groupValues[3].toInt()
                val symbolsStr = match.groupValues[4]

                val unpacked = unpackDeanEdwards(packedStr, radix, count, symbolsStr)

                // Step 2: extract fields from unpacked JSON
                val cPathMatch = Regex(""""cPath"\s*:\s*"([^"]+)"""").find(unpacked)
                val filesMatch = Regex(""""files"\s*:\s*\[([^\]]+)\]""").find(unpacked)
                val domainMatch = Regex(""""pix"\s*:\s*"([^"]+)"""").find(unpacked)

                val cPath = cPathMatch?.groupValues?.get(1) ?: ""
                val filesStr = filesMatch?.groupValues?.get(1) ?: ""
                val domain = domainMatch?.groupValues?.get(1) ?: "https://i.hamreus.com"

                if (filesStr.isEmpty()) error("漫画柜：解包成功但未找到 files 数组")

                val files = filesStr.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                files.map { file -> "$domain$cPath$file" }
            }
        }

    // ─── Dean Edwards JS Packer Decoder ──────────────────────────────────────
    //
    // Implements the standard base62/base36 symbol-table decode used by
    // the "p,a,c,k,e,d" JavaScript obfuscation pattern.
    //
    private fun unpackDeanEdwards(packed: String, radix: Int, count: Int, symbolsStr: String): String {
        val symbols = symbolsStr.split("|")

        fun decode(value: String): String {
            val base = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var num = 0
            for (ch in value) {
                num = num * radix + base.indexOf(ch)
            }
            return if (num < symbols.size && symbols[num].isNotEmpty()) symbols[num] else value
        }

        val wordPattern = Regex("""\b(\w+)\b""")
        return wordPattern.replace(packed) { matchResult ->
            decode(matchResult.value)
        }
    }

    // ─── Shared parser ────────────────────────────────────────────────────────

    private fun parseMangaList(html: String): List<Manga> {
        val doc = Jsoup.parse(html)

        // Try primary container first
        val cards = doc.select("ul#contList li, ul.book-list li, div.book-result li, .rank-list li")
            .ifEmpty { doc.select("li") }

        return cards.mapNotNull { li ->
            val link = li.selectFirst("a[href*='/comic/']") ?: return@mapNotNull null
            val href = link.attr("href")
                .let { if (it.startsWith("http")) it else "$baseUrl$it" }

            // Cover: Manhuagui lazy-loads via JS; raw HTML has data-src or style background
            val imgEl = li.selectFirst("img")
            val cover = imgEl?.attr("data-src")?.ifEmpty { null }
                ?: imgEl?.attr("src")?.ifEmpty { null }
                ?: run {
                    // Try background-image in style attribute
                    val style = li.selectFirst("[style*=background]")?.attr("style") ?: ""
                    Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
                }
                ?: return@mapNotNull null

            val cleanCover = if (cover.startsWith("//")) "https:$cover"
                             else if (cover.startsWith("http")) cover
                             else "$baseUrl$cover"

            val title = li.selectFirst("span.ell, em, .book-name, a")
                ?.text()?.trim() ?: return@mapNotNull null

            val chapter = li.selectFirst("span.new, .last-chapter, span.updateon")
                ?.text()?.trim() ?: ""

            Manga(
                title = title,
                coverUrl = cleanCover,
                detailUrl = href,
                latestChapter = chapter,
                genre = "",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }.take(24)
    }

    // ─── HTTP with domain fallback ────────────────────────────────────────────

    private fun fetch(url: String): String {
        val originalDomain = domains.firstOrNull { url.startsWith(it) } ?: baseUrl
        val path = url.removePrefix(originalDomain)

        var lastErr: Exception = IllegalStateException("No domains configured")
        for (domain in domains) {
            val target = domain + path
            try {
                val req = Request.Builder().url(target).get().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return resp.body!!.string()
                lastErr = IllegalStateException("HTTP ${resp.code} from $target")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr
    }
}
