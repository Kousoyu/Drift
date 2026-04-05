package com.kousoyu.drift.data.sources

import android.util.Log
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CopyManga (拷贝漫画) Native Source Plugin
 *
 * Decryption architecture (browser-verified 2025-04):
 *
 *   The chapter page HTML contains two values in inline <script> tags:
 *     • var cct  = 'xxxxxxxxxxxxxxxx'  ← AES key (16 chars, any printable)
 *     • var contentKey = '<IV_16chars><HEX_ciphertext>'
 *
 *   Decryption:
 *     key      = UTF-8 bytes of `cct`    (16 bytes)
 *     IV       = UTF-8 bytes of contentKey.substring(0, 16)
 *     payload  = hexToBytes(contentKey.substring(16))
 *     result   = AES-CBC-PKCS7(payload, key, IV)  → JSON with image URLs
 *
 *   The comic detail page uses `ccz` for the same key value.
 *   Variable names rotate, so we scan for the pattern rather than hard-coding names.
 *
 * Self-healing:
 *   Keys are scraped live from the chapter HTML, so key rotations require NO APK update.
 *
 * Mirror fallback:
 *   www.mangacopy.com is sometimes more stable in CN than copymanga.tv.
 *   webCandidates are tried in order; first success wins.
 */
class CopyMangaSource(private val client: OkHttpClient = buildClient()) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://www.mangacopy.com"

    /** Web mirrors in priority order. Add new mirrors here; no logic change needed. */
    private val webCandidates = listOf(
        "https://www.mangacopy.com",
        "https://copymanga.site",
        "https://copymanga.org",
        "https://copymanga.tv"
    )

    private val apiBase   = "https://api.mangacopy.com/api/v3"
    private val apiMirror = "https://api.copy20.com/api/v3"

    // ─── HTTP Headers ─────────────────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer"    to "$baseUrl/",
        "platform"   to "1",
        "region"     to "1",
        "version"    to "2.2.0"
    )

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun get(url: String, extra: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            (baseHeaders + extra).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    /** Tries each base+path combination; returns (body, successfulBase). */
    private fun getWithMirror(
        candidates: List<String>,
        path: String,
        extra: Map<String, String> = emptyMap()
    ): Pair<String, String> {
        var last: Throwable = IllegalStateException("No candidates")
        for (base in candidates) {
            val url = "${base.removeSuffix("/")}/${path.removePrefix("/")}"
            try { return Pair(get(url, extra), base) } catch (e: Exception) { last = e }
        }
        throw last
    }

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            parseMangaList(get("$apiBase/comics?ordering=-datetime_updated&offset=0&limit=30"))
        }
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            parseMangaList(get("$apiBase/search/comic?offset=0&limit=30&q=$q&q_type="))
        }
    }

    // ─── List Parser ─────────────────────────────────────────────────────────

    private fun parseMangaList(json: String): List<Manga> {
        val results = JSONObject(json).getJSONObject("results")
        val items   = results.optJSONObject("list")?.optJSONArray("list")
                    ?: results.optJSONArray("list")
                    ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val obj   = items.optJSONObject(i) ?: return@mapNotNull null
            val comic = obj.optJSONObject("comic") ?: obj
            val slug  = comic.optString("path_word").ifEmpty { return@mapNotNull null }
            val title = comic.optString("name").ifEmpty { return@mapNotNull null }
            val cover = comic.optString("cover", "")
            val author = comic.optJSONArray("author")
                ?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else "" } ?: ""
            Manga(title = title, coverUrl = cover,
                  detailUrl = "$baseUrl/comic/$slug",
                  author = author, sourceName = name)
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val slug = detailUrl.trimEnd('/').substringAfterLast("/")

            val metaJson = get("$apiBase/comic2/$slug")
            val comic    = JSONObject(metaJson).getJSONObject("results").getJSONObject("comic")
            val title    = comic.optString("name", "未知标题")
            val cover    = comic.optString("cover", "")
            val desc     = comic.optString("brief", "暂无简介")
            val status   = if (comic.optInt("status") == 1) "连载中" else "已完结"
            val author   = comic.optJSONArray("author")
                ?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else "" } ?: ""

            val chapJson = get("$apiBase/comic/$slug/group/default/chapters?limit=500&offset=0")
            val chapRes  = JSONObject(chapJson).getJSONObject("results")
            val chapArr  = chapRes.optJSONArray("list")
                        ?: chapRes.optJSONObject("list")?.optJSONArray("list")

            val webBase = webCandidates.first()
            val chapters = chapArr?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val ch   = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uuid = ch.optString("uuid").ifEmpty { return@mapNotNull null }
                    MangaChapter(ch.optString("name", "第 ${i + 1} 章"),
                                 "$webBase/comic/$slug/chapter/$uuid")
                }.reversed()
            } ?: emptyList()

            MangaDetail(url = detailUrl, title = title, coverUrl = cover,
                        author = author, description = desc, status = status, chapters = chapters)
        }
    }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // URL: /comic/{slug}/chapter/{uuid}
            val parts = chapterUrl.trimEnd('/').split("/")
            val uuid  = parts.last()
            val slug  = parts[parts.size - 3]

            // ── Step 1: Fetch chapter HTML page (with mirror fallback) ──────────
            // The chapter page contains:
            //   • var cct = '...'       ← the AES key (16 printable chars)
            //   • var contentKey = '…'  ← IV(16) + hex-ciphertext
            val (chapHtml, _) = getWithMirror(
                candidates = webCandidates,
                path       = "/comic/$slug/chapter/$uuid"
            )

            Log.d("CopyManga", "Chapter HTML length: ${chapHtml.length}, starts with: ${chapHtml.take(300)}")

            // ── Step 2: Extract AES key ────────────────────────────────────────
            // Variable name rotates (cct, ccz, dio, …) — match ANY var with exactly 16 chars.
            // Site uses single quotes: var cct = 'op0zzpvv.nmn.o0p';
            val aesKey = Regex("""var\s+\w+\s*=\s*['"](.{16})['"]""")
                .findAll(chapHtml)
                .map { it.groupValues[1] }
                .firstOrNull()
                .also { Log.d("CopyManga", "AES key found: $it") }
                ?: run {
                    Log.e("CopyManga", "KEY MISS. HTML snippet: ${chapHtml.take(2000)}")
                    error("AES key not found in chapter HTML — site may have changed obfuscation")
                }

            // ── Step 3: Extract contentKey payload ────────────────────────────
            val contentKey = Regex("""var\s+contentKey\s*=\s*'([^']+)'""")
                .find(chapHtml)?.groupValues?.get(1)

            if (contentKey != null && contentKey.length > 32) {
                // Decrypt mode: IV = first 16 chars, ciphertext = rest (hex)
                val decrypted = decryptPayload(contentKey, aesKey)
                val json      = JSONObject(decrypted)
                // The result may be nested differently, handle both known shapes
                val imgArr    = json.optJSONArray("contents")
                             ?: json.optJSONObject("results")?.optJSONArray("contents")
                             ?: error("Unexpected decrypted JSON structure")
                return@runCatching (0 until imgArr.length()).map { i ->
                    imgArr.optString(i).ifEmpty {
                        imgArr.optJSONObject(i)?.optString("url") ?: ""
                    }
                }.filter { it.isNotEmpty() }
            }

            // ── Fallback: fetch via API and decrypt each content url ───────────
            val dnts = Regex("""<span[^>]+id="dnt"[^>]+value="([^"]+)"""")
                .find(chapHtml)?.groupValues?.get(1) ?: ""

            val chapJson = try {
                get("$apiBase/comic/$slug/chapter/$uuid", mapOf("dnts" to dnts))
            } catch (_: Exception) {
                get("$apiMirror/comic/$slug/chapter/$uuid", mapOf("dnts" to dnts))
            }
            val chapterObj = JSONObject(chapJson).getJSONObject("results").getJSONObject("chapter")
            val contentsArr = chapterObj.getJSONArray("contents")

            val firstUrl = contentsArr.optJSONObject(0)?.optString("url", "") ?: ""
            (0 until contentsArr.length()).map { i ->
                val url = contentsArr.getJSONObject(i).getString("url")
                if (firstUrl.matches(Regex("[0-9a-fA-F]{33,}"))) decryptPayload(url, aesKey) else url
            }
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── AES-CBC-PKCS7 Decryption ─────────────────────────────────────────────

    /**
     * Decrypt a CopyManga content payload.
     *
     * Format: first 16 chars of [payload] → IV (UTF-8)
     *         remaining chars              → hex-encoded ciphertext
     */
    private fun decryptPayload(payload: String, key: String): String {
        val iv          = IvParameterSpec(payload.substring(0, 16).toByteArray(Charsets.UTF_8))
        val cipherBytes = hexToBytes(payload.substring(16))
        val secretKey   = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher      = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Odd hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private const val TAG = "CopyManga"

        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // In-memory cookie jar: lets us share session cookies across requests
            // (some chapter pages require the session cookie set on the domain root)
            .cookieJar(object : CookieJar {
                private val store = mutableMapOf<String, MutableList<Cookie>>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    store[url.host] ?: emptyList()
            })
            .build()
    }
}
