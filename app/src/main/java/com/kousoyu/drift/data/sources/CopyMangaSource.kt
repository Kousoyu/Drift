package com.kousoyu.drift.data.sources

import android.util.Base64
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
 *  - Manga list / search → JSON API (api.mangacopy.com/api/v3)
 *  - Chapter images      → JSON API with AES-CBC decryption
 *
 * Decryption design (self-healing):
 *  The AES key is NOT hardcoded. It is scraped LIVE from the comic's detail
 *  page HTML (inside a <script> tag, variable name changes periodically but
 *  is always a 16-char hex string).  This means even if CopyManga rotates
 *  the key, the decryption continues to work WITHOUT any APK update.
 *
 *  Decryption algorithm:
 *    cipher   = AES/CBC/PKCS7Padding
 *    key      = 16-char string from comic page script tag
 *    IV       = first 16 characters of the `results` field (UTF-8 bytes)
 *    payload  = remaining characters of `results`, hex-decoded to bytes
 */
class CopyMangaSource(private val client: OkHttpClient = buildClient()) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://copymanga.tv"

    private val apiBase  = "https://api.mangacopy.com/api/v3"
    private val apiMirror = "https://api.copy20.com/api/v3"

    // ─── Required request headers ─────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36",
        "Referer"    to "$baseUrl/",
        "platform"   to "1",        // 1 = Mobile app client
        "region"     to "1",
        "version"    to "2.2.0"
    )

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            (baseHeaders + extraHeaders).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("CopyManga HTTP ${res.code} @ $url")
        return res.body!!.string()
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
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$apiBase/search/comic?offset=0&limit=30&q=$encoded&q_type="
            parseMangaList(get(url))
        }
    }

    // ─── List Parser ─────────────────────────────────────────────────────────

    private fun parseMangaList(json: String): List<Manga> {
        val root    = JSONObject(json)
        val results = root.getJSONObject("results")
        // Two possible keys depending on endpoint
        val list = results.optJSONObject("list") ?: results.optJSONObject("comic")
        val items = list?.optJSONArray("list") ?: results.optJSONArray("list") ?: return emptyList()

        return (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            // Comic list items embed the comic object, detail items are flat
            val comic   = obj.optJSONObject("comic") ?: obj
            val slug    = comic.optString("path_word").ifEmpty { return@mapNotNull null }
            val title   = comic.optString("name").ifEmpty { return@mapNotNull null }
            val cover   = comic.optString("cover")
            val author  = comic.optJSONArray("author")
                ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(0).optString("name") else "" }
                ?: ""

            Manga(
                title         = title,
                coverUrl      = cover,
                detailUrl     = "$baseUrl/comic/$slug",
                author        = author,
                sourceName    = name
            )
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val slug = detailUrl.substringAfterLast("/")

            // 1. Comic metadata
            val metaJson   = get("$apiBase/comic2/$slug")
            val comicObj   = JSONObject(metaJson).getJSONObject("results").getJSONObject("comic")
            val title      = comicObj.optString("name", "未知标题")
            val cover      = comicObj.optString("cover", "")
            val desc       = comicObj.optString("brief", "暂无简介")
            val status     = if (comicObj.optInt("status") == 1) "连载中" else "已完结"
            val authorArr  = comicObj.optJSONArray("author")
            val author     = if (authorArr != null && authorArr.length() > 0)
                                 authorArr.getJSONObject(0).optString("name") else ""

            // 2. Chapter list (fetch up to 500 chapters from default group)
            val chapJson  = get("$apiBase/comic/$slug/group/default/chapters?limit=500&offset=0")
            val chapList  = JSONObject(chapJson).getJSONObject("results").optJSONArray("list")
                            ?: JSONObject(chapJson).getJSONObject("results").getJSONObject("list").optJSONArray("list")

            val chapters  = if (chapList != null) {
                (0 until chapList.length()).mapNotNull { i ->
                    val ch    = chapList.optJSONObject(i) ?: return@mapNotNull null
                    val uuid  = ch.optString("uuid").ifEmpty { return@mapNotNull null }
                    val name  = ch.optString("name", "章节 ${i + 1}")
                    MangaChapter(name, "$baseUrl/comic/$slug/chapter/$uuid")
                }.reversed()  // oldest first
            } else emptyList()

            MangaDetail(
                url         = detailUrl,
                title       = title,
                coverUrl    = cover,
                author      = author,
                description = desc,
                status      = status,
                chapters    = chapters
            )
        }
    }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // URL pattern: /comic/{slug}/chapter/{uuid}
            val parts  = chapterUrl.trimEnd('/').split("/")
            val uuid   = parts.last()
            val slug   = parts[parts.size - 3]

            // Step 1 — Get the comic page to extract the live AES key
            val comicPageHtml = get("$baseUrl/comic/$slug")
            val aesKey        = extractAesKey(comicPageHtml)
                ?: error("找不到 AES 密钥，拷贝漫画可能更新了反爬机制")

            // Step 2 — Get dnts token from comic page (required anti-bot header)
            val dnts = Regex("""<input[^>]+id="dnt"[^>]+value="([^"]+)"""")
                .find(comicPageHtml)?.groupValues?.get(1) ?: ""

            // Step 3 — Fetch encrypted chapter content
            val chapJson   = get(
                url = "$apiBase/comic/$slug/chapter/$uuid",
                extraHeaders = mapOf("dnts" to dnts)
            )
            val root       = JSONObject(chapJson)
            val resultStr  = root.getJSONObject("results").getJSONObject("chapter").optString("contents")
                             ?: root.getJSONObject("results").getString("chapter")
            // Some endpoints encode the whole chapter object as the encrypted string
            val encrypted  = root.getJSONObject("results").optString("chapter").takeIf {
                it.length > 32 && !it.startsWith("{")
            } ?: run {
                // Contents is an array with encrypted url fields
                val contents = root.getJSONObject("results")
                    .getJSONObject("chapter").getJSONArray("contents")
                return@runCatching (0 until contents.length()).map { i ->
                    val url = contents.getJSONObject(i).getString("url")
                    decryptImageUrl(url, aesKey)
                }
            }

            // Step 4 — Decrypt the main payload
            val decrypted = decryptPayload(encrypted, aesKey)
            val imgArray  = JSONObject(decrypted).getJSONArray("contents")
            (0 until imgArray.length()).map { i ->
                imgArray.getJSONObject(i).getString("url")
            }
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── AES Decryption Engine ────────────────────────────────────────────────

    /**
     * Extracts the live AES key from the comic HTML page.
     *
     * CopyManga embeds the key in a <script> tag as a 16-char hex string
     * assigned to a variable whose name rotates (currently "ccz", historically "dio").
     * We scan all such patterns to be resilient to variable renames.
     *
     * Pattern: var {anyName} = "{16 hex chars}";
     */
    private fun extractAesKey(html: String): String? {
        // Broad pattern: any JS var = "exactly 16 hex characters"
        val pattern = Regex("""var\s+\w+\s*=\s*"([0-9a-f]{16})"""", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.get(1)
    }

    /**
     * Decrypt an individual encrypted image URL string.
     *
     * Format: first 16 chars = IV (UTF-8), remaining = hex-encoded ciphertext
     */
    private fun decryptImageUrl(encrypted: String, key: String): String {
        if (!encrypted.matches(Regex("[0-9a-fA-F]{33,}"))) return encrypted // already plain URL
        return try {
            decryptPayload(encrypted, key)
        } catch (_: Exception) {
            encrypted  // fallback: return as-is
        }
    }

    /**
     * Core AES-CBC-PKCS7 decryption.
     *
     * Input  : hex-prefixed string where:
     *            - chars [0..15]  → IV (UTF-8 bytes)
     *            - chars [16..]   → hex-encoded ciphertext
     * Output : decrypted UTF-8 string (usually a JSON block)
     */
    private fun decryptPayload(payload: String, key: String): String {
        val ivBytes       = payload.substring(0, 16).toByteArray(Charsets.UTF_8)
        val cipherHex     = payload.substring(16)
        val cipherBytes   = hexToBytes(cipherHex)

        val secretKey     = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec        = IvParameterSpec(ivBytes)
        val cipher        = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex string length: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ─── Factory ─────────────────────────────────────────────────────────────

    companion object {
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
