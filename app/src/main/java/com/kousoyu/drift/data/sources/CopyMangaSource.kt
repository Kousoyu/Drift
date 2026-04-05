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
 * Architecture (confirmed by browser inspection + OkHttp logcat diagnostics):
 *
 *   The site is a Vue.js SPA. The AES key (`var cct`) is NOT in the static HTML.
 *   It lives in an external JS file:
 *     https://s3.mangafunb.fun/static/websitefree/js20190704/comic_content_pass{version}.js
 *
 *   The `version` (e.g. "202508141534") is returned by the chapter API response
 *   as a field named `js_version` (or equivalent). This keeps our code self-healing:
 *   when CopyManga rotates the key, the API response carries the new version string,
 *   we fetch the new JS file, and decryption continues without any APK update.
 *
 *   Decryption (AES-CBC-PKCS7):
 *     key   = UTF-8 bytes of `cct` (16 chars from pass JS)
 *     IV    = UTF-8 bytes of contentKey[0..15]
 *     data  = hexToBytes(contentKey[16..])
 *
 *   Mirror fallback:
 *     The API (api.mangacopy.com) is usually accessible in mainland China directly.
 *     Web pages (for HTML/JS fetches) try webCandidates[] in order.
 */
class CopyMangaSource(private val client: OkHttpClient = buildClient()) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://www.mangacopy.com"

    private val webCandidates = listOf(
        "https://www.mangacopy.com",
        "https://copymanga.site",
        "https://copymanga.org",
        "https://copymanga.tv"
    )

    private val apiBase   = "https://api.mangacopy.com/api/v3"
    private val apiMirror = "https://api.copy20.com/api/v3"

    // Base URL for S3 static assets (JS files, images, etc.)
    private val s3Base    = "https://s3.mangafunb.fun"

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
            val parts = chapterUrl.trimEnd('/').split("/")
            val uuid  = parts.last()
            val slug  = parts[parts.size - 3]

            // ── Step 1: Call chapter API ───────────────────────────────────────
            // The response may contain `js_version` which identifies the current
            // comic_content_pass JS file holding the AES key.
            val chapJson = try {
                get("$apiBase/comic/$slug/chapter/$uuid")
            } catch (_: Exception) {
                get("$apiMirror/comic/$slug/chapter/$uuid")
            }
            Log.d("CopyManga", "API response (600): ${chapJson.take(600)}")

            val results  = JSONObject(chapJson).getJSONObject("results")
            val chapObj  = results.getJSONObject("chapter")
            Log.d("CopyManga", "chapter field keys: ${chapObj.keys().asSequence().joinToString()}")

            // ── Step 2: Check if content is already plain URLs ─────────────────
            val contentsArr = chapObj.optJSONArray("contents")
            val firstUrl    = contentsArr?.optJSONObject(0)?.optString("url", "") ?: ""
            Log.d("CopyManga", "firstUrl sample: ${firstUrl.take(80)}")

            if (contentsArr != null && firstUrl.startsWith("http")) {
                Log.d("CopyManga", "Contents are plain URLs, returning directly")
                return@runCatching (0 until contentsArr.length()).map { i ->
                    contentsArr.getJSONObject(i).getString("url")
                }
            }

            // ── Step 3: Find the pass JS version ──────────────────────────────
            // Try: js_version in chapter obj, results obj, or search by pattern
            val jsVersion = chapObj.optString("js_version").ifEmpty { null }
                         ?: results.optString("js_version").ifEmpty { null }
                         ?: chapObj.optString("passVersion").ifEmpty { null }
            Log.d("CopyManga", "js_version from API: $jsVersion")

            // ── Step 4: Find pass JS URL ───────────────────────────────────────
            val passJsUrl: String? = if (jsVersion != null) {
                "$s3Base/static/websitefree/js20190704/comic_content_pass$jsVersion.js"
            } else {
                // Fallback: fetch chapter page HTML, find app bundle, search inside it
                val (chapHtml, _) = getWithMirror(
                    candidates = webCandidates,
                    path       = "/comic/$slug/chapter/$uuid"
                )
                Log.d("CopyManga", "HTML length: ${chapHtml.length}")

                // Direct URL in HTML
                var found = Regex("""(https://[^\s"'<>]+comic_content_pass[^\s"'<>]+\.js)""")
                    .find(chapHtml)?.groupValues?.get(1)

                // Search inside Vue app bundles for js20190704 reference
                if (found == null) {
                    val appBundles = Regex("""<script[^>]+src=["']?(https://s3\.mangafunb\.fun/[^\s"'<>]+\.js)["']?""")
                        .findAll(chapHtml).map { it.groupValues[1] }
                        .filter { "cdn" !in it }.toList()
                    Log.d("CopyManga", "app bundles: $appBundles")

                    for (bundleUrl in appBundles) {
                        try {
                            val js = get(bundleUrl)
                            // Look for js20190704 folder reference (less likely to be split)
                            val snippet = Regex("""js20190704[^\s"']{0,100}""").find(js)?.value
                            Log.d("CopyManga", "js20190704 snippet in $bundleUrl: $snippet")

                            found = Regex("""https://[^\s"']+js20190704[^\s"']+\.js""").find(js)?.value
                                ?: snippet?.let {
                                    Regex("""comic_content_pass([\w.]+)\.js""").find(it)
                                        ?.groupValues?.get(1)
                                        ?.let { ver -> "$s3Base/static/websitefree/js20190704/comic_content_pass$ver.js" }
                                }
                            if (found != null) break
                        } catch (e: Exception) {
                            Log.w("CopyManga", "bundle fetch error: ${e.message}")
                        }
                    }
                }
                found
            }
            Log.d("CopyManga", "passJsUrl: $passJsUrl")

            // ── Step 5: Fetch pass JS and extract AES key ─────────────────────
            if (passJsUrl == null) {
                error("Cannot locate comic_content_pass JS (js_version not in API, not in HTML)")
            }

            val passJs = try { get(passJsUrl) } catch (e: Exception) {
                error("Failed to fetch pass JS ($passJsUrl): ${e.message}")
            }
            Log.d("CopyManga", "pass JS length: ${passJs.length}, snippet: ${passJs.take(200)}")

            val aesKey = Regex("""var\s+\w+\s*=\s*['"](.{16})['"]""")
                .findAll(passJs).map { it.groupValues[1] }.firstOrNull()
                ?: error("AES key not found in pass JS. Snippet: ${passJs.take(300)}")
            Log.d("CopyManga", "AES key: $aesKey")

            // ── Step 6: Decrypt ───────────────────────────────────────────────
            if (contentsArr != null && firstUrl.matches(Regex("[0-9a-fA-F]{33,}"))) {
                // Per-image encrypted mode
                return@runCatching (0 until contentsArr.length()).map { i ->
                    decryptPayload(contentsArr.getJSONObject(i).getString("url"), aesKey)
                }
            }

            // Whole-blob encrypted mode (contentKey = IV + hex-ciphertext)
            val contentKey = chapObj.optString("content_key").ifEmpty { null }
                          ?: chapObj.optString("contentKey").ifEmpty { null }
            if (contentKey != null && contentKey.length > 32) {
                val decrypted = decryptPayload(contentKey, aesKey)
                val imgArr    = JSONObject(decrypted).optJSONArray("contents")
                             ?: error("Unexpected decrypted JSON: ${decrypted.take(200)}")
                return@runCatching (0 until imgArr.length()).mapNotNull { i ->
                    imgArr.optJSONObject(i)?.optString("url")?.takeIf { it.isNotEmpty() }
                }
            }

            // Last resort: return whatever is in contents
            if (contentsArr != null && contentsArr.length() > 0) {
                return@runCatching (0 until contentsArr.length()).map { i ->
                    contentsArr.getJSONObject(i).getString("url")
                }
            }

            error("No decryptable content found in API response")
        }
    }

    override fun getHeaders(): Map<String, String> = baseHeaders

    // ─── AES-CBC-PKCS7 Decryption ─────────────────────────────────────────────

    /**
     * payload: first 16 chars → IV (UTF-8), rest → hex-encoded ciphertext
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
        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
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
