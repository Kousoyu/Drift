package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * JMComic (禁漫天堂) — R18 source
 *
 * Strategy: All web mirrors block plain HTTP clients with 403.
 * The "public" API domains (jmapiproxy*) are dead/domain-parked.
 * This source is implemented as a **gracefully-failing stub** that:
 *   - Cleanly reports the mirror-wall error to the UI
 *   - Contains the full scramble-decode algorithm ready for when
 *     a working bypass (WebView cookie injection / VPN) is added
 *
 * To integrate in a future phase:
 *   1. Add a WebView-based cookie harvester in the app
 *   2. Pass cookies into the OkHttpClient here
 *   3. Switch mirrorStatus to ACTIVE
 *
 * ═══════════════════════════════════════════════════════
 * IMAGE SCRAMBLE DECODER — Ready for activation
 * ═══════════════════════════════════════════════════════
 * JMComic uses "推箱子 (image shuffle)" for albums with id >= ~268850.
 * Each page image is sliced into N horizontal strips then reordered.
 * The strip count N = (MD5(key + albumId + filename).last8hex % 10) + 2
 * The reader layer uses this to reconstruct pixel-correct images.
 */
class JMComicSource : MangaSource {
    override val name = "禁漫天堂"
    // Web mirrors — all currently return 403 to OkHttp clients
    // Phase 17 target: inject WebView cookies to bypass
    private val webMirrors = listOf(
        "https://18comic.vip",
        "https://18comic.org",
        "https://jmcomic1.me",
        "https://jmcomic.me",
        "https://18comic.fun"
    )
    override val baseUrl = webMirrors.first()

    // Scramble constants
    val scrambleThreshold = 268850
    val scrambleKey = "18comicFun18comicKey"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(DriftDns)   // ← DoH bypass: defeats the poisoned IPv6 returned by mainland ISPs
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-TW,zh;q=0.9")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(req)
        }
        .build()

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetchWithMirrorFallback("/albums?o=mr&page=1")
                parseAlbumListHtml(html)
            }
        }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val html = fetchWithMirrorFallback("/search/photos?main_tag=0&search_query=$encoded&page=1")
                parseAlbumListHtml(html)
            }
        }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetchWithMirrorFallback(detailUrl.removePrefix(baseUrl))
                val doc = Jsoup.parse(html)

                val title = doc.selectFirst("div.panel-heading h3.title")?.text()?.trim()
                    ?: doc.selectFirst("h1, .book-title")?.text()?.trim() ?: "未知标题"

                val cover = doc.selectFirst("div.thumb-overlay img, img.lazy_img")
                    ?.let { it.attr("data-original").ifEmpty { it.attr("src") } } ?: ""

                val author = doc.select("div.tag-block a.tag").map { it.text() }
                    .joinToString("、").ifEmpty { "未知" }

                val desc = doc.selectFirst("div.p-t-5.p-b-5")?.text()?.trim() ?: "暂无简介"

                val status = "连载中"

                val chapters = doc.select("ul.btn-toolbar a.btn[href*='/photo/']").mapNotNull { a ->
                    val href = a.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }
                    val name = a.text().trim().ifEmpty { null } ?: return@mapNotNull null
                    MangaChapter(name, href)
                }.distinctBy { it.url }

                MangaDetail(detailUrl, title, cover, author, desc, status, chapters)
            }
        }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = fetchWithMirrorFallback(chapterUrl.removePrefix(baseUrl))
                val doc = Jsoup.parse(html)

                // Extract album ID for scramble detection
                val albumId = Regex("""/album/(\d+)""").find(html)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val needsUnscramble = albumId >= scrambleThreshold

                val urls = doc.select("div.scramble-page img, div.read-page img, img.lazy_img")
                    .mapNotNull { img ->
                        val src = img.attr("data-original").ifEmpty { img.attr("src") }
                        src.takeIf { it.isNotEmpty() && (it.contains("cdn") || it.contains("jpg") || it.contains("png")) }
                    }

                if (urls.isEmpty()) error("禁漫天堂：未找到图片（可能镜像节点拦截了本次请求）")

                urls.map { url ->
                    if (needsUnscramble) "$url#jm_scramble=$albumId" else url
                }
            }
        }

    // ─── Scramble Algorithm ───────────────────────────────────────────────────

    fun calculateScrambleBlocks(albumId: Int, filename: String): Int {
        val md5Input = "$scrambleKey$albumId$filename"
        val md5Bytes = java.security.MessageDigest.getInstance("MD5")
            .digest(md5Input.toByteArray())
        val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }
        val decimal = md5Hex.takeLast(8).toLongOrNull(16) ?: 0L
        return (decimal % 10 + 2).toInt()
    }

    // ─── Mirror-resilient HTML fetch ──────────────────────────────────────────

    private fun fetchWithMirrorFallback(path: String): String {
        var lastErr: Exception = IllegalStateException("No mirrors")
        for (mirror in webMirrors) {
            val url = if (path.startsWith("http")) path else "$mirror$path"
            try {
                val req = Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return resp.body!!.string()
                if (resp.code == 403) {
                    lastErr = IllegalStateException(
                        "禁漫天堂：镜像节点 $mirror 拒绝了连接（HTTP 403）。\n" +
                        "此图源需要登录Cookie才能访问，将在后续版本中通过WebView验证解锁。"
                    )
                } else {
                    lastErr = IllegalStateException("HTTP ${resp.code} from $url")
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr
    }

    // ─── HTML album list parser ────────────────────────────────────────────────

    private fun parseAlbumListHtml(html: String): List<Manga> {
        val doc = Jsoup.parse(html)
        return doc.select("div.p-b-15, div.thumb-overlay-albums").mapNotNull { card ->
            val link = card.selectFirst("a[href*='/album/']") ?: return@mapNotNull null
            val href = link.attr("href").let { if (it.startsWith("http")) it else "$baseUrl$it" }

            val img = card.selectFirst("img")
            val cover = img?.attr("data-original")?.ifEmpty { img.attr("src") } ?: ""
            if (cover.isEmpty()) return@mapNotNull null

            val title = card.selectFirst("span.video-title, div.title")
                ?.text()?.trim() ?: link.attr("title").trim().ifEmpty { null }
                ?: return@mapNotNull null

            Manga(
                title = title,
                coverUrl = cover,
                detailUrl = href,
                latestChapter = "",
                genre = card.selectFirst("div.category-label")?.text()?.trim() ?: "",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }.take(20)
    }
}
