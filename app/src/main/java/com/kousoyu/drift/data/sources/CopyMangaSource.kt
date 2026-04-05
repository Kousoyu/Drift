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
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CopyManga (拷贝漫画) Native Source Plugin
 *
 * Architecture:
 *   - Manga list / search  → JSON API  (api.mangacopy.com/api/v3)
 *   - Chapter images       → JSON API  + AES-CBC decryption
 *
 * Self-healing AES key design:
 *   The AES key is NOT hardcoded. It is scraped live from the comic's detail
 *   page HTML (a 16-char hex string in a <script> variable, name changes
 *   periodically: "ccz", "dio", etc.).
 *   Even when CopyManga rotates the key, decryption continues to work WITHOUT
 *   any APK update.
 *
 * Mirror fallback:
 *   The web domain (copymanga.tv) is often DNS-blocked in mainland China.
 *   [webCandidates] lists alternatives in priority order; the first that
 *   responds is used for the AES key page fetch. The API endpoint is separate
 *   and usually accessible directly.
 */
class CopyMangaSource(private val client: OkHttpClient = buildClient()) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://copymanga.site"

    /**
     * Web mirrors in priority order.
     * Add new mirrors here when old ones get blocked — no logic change needed.
     */
    private val webCandidates = listOf(
        "https://copymanga.site",    // most stable CN-accessible mirror
        "https://copymanga.org",
        "https://www.mangacopy.com",
        "https://copymanga.tv",      // original, often DNS-blocked in CN
        "https://copy20.com"
    )

    private val apiBase   = "https://api.mangacopy.com/api/v3"
    private val apiMirror = "https://api.copy20.com/api/v3"

    // ─── Required request headers ─────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36",
        "Referer"    to "$baseUrl/",
        "platform"   to "1",
        "region"     to "1",
        "version"    to "2.2.0"
    )

    // ─── HTTP ────────────────────────────────────────────────────────────────

    /** Single-URL GET. Throws on non-2xx. */
    private fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            (baseHeaders + extraHeaders).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("CopyManga HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    /**
     * GET with automatic mirror fallback.
     * Tries [candidates[0]/path], then [candidates[1]/path], etc.
     * Returns (responseBody, successfulBase).
     */
    private fun getWithMirror(
        candidates: List<String>,
        path: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<String, String> {
        var lastErr: Throwable = IllegalStateException("No candidates configured")
        for (base in candidates) {
            val url = "${base.removeSuffix("/")}/${path.removePrefix("/")}"
            try {
                return Pair(get(url, extraHeaders), base)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr
    }

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$apiBase/comics?ordering=-datetime_updated&offset=0&limit=30&_update=true"
            parseMangaList(get(url))
        }
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> = withContext(Dispatchers.IO) {
        runCatching {
            val enc = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            parseMangaList(get("$apiBase/search/comic?offset=0&limit=30&q=$enc&q_type="))
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

            // Metadata via API
            val metaJson  = get("$apiBase/comic2/$slug")
            val comicObj  = JSONObject(metaJson).getJSONObject("results").getJSONObject("comic")
            val title     = comicObj.optString("name", "未知标题")
            val cover     = comicObj.optString("cover", "")
            val desc      = comicObj.optString("brief", "暂无简介")
            val status    = if (comicObj.optInt("status") == 1) "连载中" else "已完结"
            val author    = comicObj.optJSONArray("author")
                ?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else "" } ?: ""

            // Chapter list — chapters link to web mirror, uuid is embedded
            val chapJson  = get("$apiBase/comic/$slug/group/default/chapters?limit=500&offset=0")
            val chapResults = JSONObject(chapJson).getJSONObject("results")
            val chapList  = chapResults.optJSONArray("list")
                         ?: chapResults.optJSONObject("list")?.optJSONArray("list")

            // Use webCandidates[0] as the base for chapter detail URLs so they
            // can be resolved even without a VPN (the slug/uuid is what matters).
            val webBase   = webCandidates.first()
            val chapters  = chapList?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val ch   = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uuid = ch.optString("uuid").ifEmpty { return@mapNotNull null }
                    val cname = ch.optString("name", "章节 ${i + 1}")
                    MangaChapter(cname, "$webBase/comic/$slug/chapter/$uuid")
                }.reversed()
            } ?: emptyList()

            MangaDetail(url = detailUrl, title = title, coverUrl = cover,
                        author = author, description = desc, status = status, chapters = chapters)
        }
    }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Parse slug & uuid from URL pattern: /comic/{slug}/chapter/{uuid}
            val parts = chapterUrl.trimEnd('/').split("/")
            val uuid  = parts.last()
            val slug  = parts[parts.size - 3]

            // Step 1 — Fetch comic HTML **with mirror fallback** to extract live AES key
            val (comicPageHtml, _) = getWithMirror(
                candidates = webCandidates,
                path       = "/comic/$slug"
            )
            val aesKey = extractAesKey(comicPageHtml)
                ?: error("找不到 AES 密钥 — 拷贝漫画可能更新了混淆变量名，请检查")

            // Step 2 — Extract dnts anti-bot token from the same HTML page
            val dnts = Regex("""<input[^>]+id="dnt"[^>]+value="([^"]+)"""")
                .find(comicPageHtml)?.groupValues?.get(1) ?: ""

            // Step 3 — Fetch encrypted chapter content (API usually works directly)
            val chapJson = try {
                get("$apiBase/comic/$slug/chapter/$uuid", mapOf("dnts" to dnts))
            } catch (_: Exception) {
                get("$apiMirror/comic/$slug/chapter/$uuid", mapOf("dnts" to dnts))
            }

            val chapterObj = JSONObject(chapJson)
                .getJSONObject("results")
                .getJSONObject("chapter")

            // Step 4 — Decrypt image URLs
            // Mode A: `contents` is a JSON array, each item has an (encrypted/plain) `url`
            val contentsArr = chapterObj.optJSONArray("contents")
            if (contentsArr != null && contentsArr.length() > 0) {
                val firstUrl = contentsArr.getJSONObject(0).optString("url", "")
                return@runCatching if (firstUrl.matches(Regex("[0-9a-fA-F]{33,}"))) {
                    // Encrypted mode: each url is individually encrypted hex string
                    (0 until contentsArr.length()).map { i ->
                        decryptPayload(contentsArr.getJSONObject(i).getString("url"), aesKey)
                    }
                } else {
                    // Plain URL mode — no decryption needed
                    (0 until contentsArr.length()).map { i ->
                        contentsArr.getJSONObject(i).getString("url")
                    }
                }
            }

            // Mode B: `contents` is one big encrypted hex blob
            val blob      = chapterObj.getString("contents")
            val decrypted = decryptPayload(blob, aesKey)
            val imgArr    = JSONObject(decrypted).getJSONArray("contents")
            (0 until imgArr.length()).map { i -> imgArr.getJSONObject(i).getString("url") }
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── AES Decryption Engine ────────────────────────────────────────────────

    /**
     * Extracts the live AES key from the comic HTML page.
     *
     * CopyManga embeds a 16-char hex key in a <script> tag.
     * The JS variable name rotates (historically: "ccz", "dio", "labi", …).
     * We scan for ANY variable holding exactly 16 hex chars, so variable renames
     * never break decryption.
     */
    private fun extractAesKey(html: String): String? =
        Regex("""var\s+\w+\s*=\s*"([0-9a-f]{16})"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            // Exclude generic hex strings that look like IDs (prefer script-embedded ones)
            .firstOrNull()

    /**
     * AES-CBC-PKCS7 decryption.
     *
     * Payload format:
     *   chars [0..15]   → IV (interpreted as UTF-8 bytes)
     *   chars [16..]    → hex-encoded ciphertext
     */
    private fun decryptPayload(payload: String, key: String): String {
        val ivBytes     = payload.substring(0, 16).toByteArray(Charsets.UTF_8)
        val cipherBytes = hexToBytes(payload.substring(16))
        val secretKey   = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher      = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // ─── Factory ─────────────────────────────────────────────────────────────

    companion object {
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
