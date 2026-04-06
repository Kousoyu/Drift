package com.kousoyu.drift.data.sources

import android.util.Log
import com.kousoyu.drift.data.HttpEngine
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CopyManga (拷贝漫画) — JSON API with self-healing AES decryption.
 *
 * The AES key lives in an external JS file whose version is returned by the API,
 * making key rotations transparent — no APK update needed.
 */
class CopyMangaSource(client: OkHttpClient) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://www.mangacopy.com"

    private val apiHeaders = mapOf(
        "User-Agent" to UA,
        "Referer"    to "$baseUrl/",
        "platform"   to "1",
        "region"     to "1",
        "version"    to "2.2.0"
    )

    /** API engine — tries primary then mirror. */
    private val api = HttpEngine(
        client  = client,
        mirrors = listOf("https://api.mangacopy.com/api/v3", "https://api.copy20.com/api/v3"),
        headers = apiHeaders
    )

    /** Web engine — for scraping pass JS when needed. */
    private val web = HttpEngine(
        client  = client,
        mirrors = listOf("https://www.mangacopy.com", "https://copymanga.site", "https://copymanga.org"),
        headers = apiHeaders
    )

    private val s3Base = "https://s3.mangafunb.fun"

    // ─── Popular / Search ────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching { parseMangaList(api.fetch("/comics?ordering=-datetime_updated&offset=0&limit=30")) }
    }

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            parseMangaList(api.fetch("/search/comic?offset=0&limit=30&q=$q&q_type="))
        }
    }

    private fun parseMangaList(json: String): List<Manga> {
        val results = JSONObject(json).getJSONObject("results")
        val items   = results.optJSONObject("list")?.optJSONArray("list")
                    ?: results.optJSONArray("list") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val comic = items.optJSONObject(i)?.let { it.optJSONObject("comic") ?: it } ?: return@mapNotNull null
            val slug  = comic.optString("path_word").ifEmpty { return@mapNotNull null }
            val title = comic.optString("name").ifEmpty { return@mapNotNull null }
            val cover = comic.optString("cover", "")
            val author = comic.optJSONArray("author")
                ?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else "" } ?: ""
            Manga(title = title, coverUrl = cover, detailUrl = "$baseUrl/comic/$slug",
                  author = author, sourceName = name)
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val slug = detailUrl.trimEnd('/').substringAfterLast("/")
            val comic = JSONObject(api.fetch("/comic2/$slug")).getJSONObject("results").getJSONObject("comic")

            val chapJson = api.fetch("/comic/$slug/group/default/chapters?limit=500&offset=0")
            val chapArr  = JSONObject(chapJson).getJSONObject("results").let { r ->
                r.optJSONArray("list") ?: r.optJSONObject("list")?.optJSONArray("list")
            }

            val chapters = chapArr?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val ch = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uuid = ch.optString("uuid").ifEmpty { return@mapNotNull null }
                    MangaChapter(ch.optString("name", "第 ${i + 1} 章"),
                                 "$baseUrl/comic/$slug/chapter/$uuid")
                }.reversed()
            } ?: emptyList()

            MangaDetail(
                url         = detailUrl,
                title       = comic.optString("name", "未知标题"),
                coverUrl    = comic.optString("cover", ""),
                author      = comic.optJSONArray("author")
                    ?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else "" } ?: "",
                description = comic.optString("brief", "暂无简介"),
                status      = if (comic.optInt("status") == 1) "连载中" else "已完结",
                chapters    = chapters
            )
        }
    }

    // ─── Chapter Images ──────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = chapterUrl.trimEnd('/').split("/")
            val uuid  = parts.last()
            val slug  = parts[parts.size - 3]

            val chapJson = api.fetch("/comic/$slug/chapter/$uuid")
            val results  = JSONObject(chapJson).getJSONObject("results")
            val chapObj  = results.getJSONObject("chapter")
            val contents = chapObj.optJSONArray("contents")
            val firstUrl = contents?.optJSONObject(0)?.optString("url", "") ?: ""

            // ── Plain URL mode (most common) ────────────────────────────────
            if (firstUrl.startsWith("http")) {
                return@runCatching (0 until contents!!.length()).map { contents.getJSONObject(it).getString("url") }
            }

            // ── Encrypted mode — find AES key ──────────────────────────────
            val jsVersion = chapObj.optString("js_version").ifEmpty { null }
                          ?: results.optString("js_version").ifEmpty { null }

            val passJsUrl = if (jsVersion != null) {
                "$s3Base/static/websitefree/js20190704/comic_content_pass$jsVersion.js"
            } else {
                findPassJsUrl(slug, uuid)
            } ?: error("Cannot locate comic_content_pass JS")

            val aesKey = Regex("""var\s+\w+\s*=\s*['"](.{16})['"]""")
                .findAll(api.fetchDirect(passJsUrl)).map { it.groupValues[1] }.firstOrNull()
                ?: error("AES key not found in $passJsUrl")

            // ── Per-image hex decryption ────────────────────────────────────
            if (firstUrl.matches(Regex("[0-9a-fA-F]{33,}"))) {
                return@runCatching (0 until contents!!.length()).map {
                    decrypt(contents.getJSONObject(it).getString("url"), aesKey)
                }
            }

            // ── Whole-blob contentKey mode ──────────────────────────────────
            val contentKey = chapObj.optString("content_key").ifEmpty { null }
                           ?: chapObj.optString("contentKey").ifEmpty { null }
            if (contentKey != null && contentKey.length > 32) {
                val arr = JSONObject(decrypt(contentKey, aesKey)).optJSONArray("contents")
                    ?: error("Unexpected decrypted JSON")
                return@runCatching (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotEmpty() }
                }
            }

            // ── Fallback ────────────────────────────────────────────────────
            if (contents != null && contents.length() > 0) {
                return@runCatching (0 until contents.length()).map { contents.getJSONObject(it).getString("url") }
            }
            error("No content found in chapter API response")
        }
    }

    override fun getHeaders(): Map<String, String> = apiHeaders

    // ─── Pass JS Discovery ──────────────────────────────────────────────────

    private fun findPassJsUrl(slug: String, uuid: String): String? {
        val html = web.fetch("/comic/$slug/chapter/$uuid")

        // Direct link in HTML
        Regex("""(https://[^\s"'<>]+comic_content_pass[^\s"'<>]+\.js)""")
            .find(html)?.groupValues?.get(1)?.let { return it }

        // Scan Vue app bundles
        Regex("""<script[^>]+src=["']?(https://s3\.mangafunb\.fun/[^\s"'<>]+\.js)["']?""")
            .findAll(html).map { it.groupValues[1] }.filter { "cdn" !in it }
            .forEach { bundleUrl ->
                try {
                    val js = api.fetchDirect(bundleUrl)
                    Regex("""https://[^\s"']+js20190704[^\s"']+\.js""").find(js)?.value?.let { return it }
                    Regex("""comic_content_pass([\w.]+)\.js""").find(js)?.groupValues?.get(1)?.let { ver ->
                        return "$s3Base/static/websitefree/js20190704/comic_content_pass$ver.js"
                    }
                } catch (e: Exception) {
                    Log.w("CopyManga", "bundle scan: ${e.message}")
                }
            }
        return null
    }

    // ─── AES-CBC Decryption ─────────────────────────────────────────────────

    private fun decrypt(payload: String, key: String): String {
        val iv = IvParameterSpec(payload.substring(0, 16).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), iv)
        return String(cipher.doFinal(hexToBytes(payload.substring(16))), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Odd hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
