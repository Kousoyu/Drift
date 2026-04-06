package com.kousoyu.drift.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kousoyu.drift.data.HttpEngine
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CopyManga (拷贝漫画) — JSON API with self-healing AES decryption.
 *
 * Supports token-based authentication for full chapter access.
 * Without a token, the API limits chapters to 5 pages.
 */
class CopyMangaSource(private val client: OkHttpClient, context: Context? = null) : MangaSource {

    override val name    = "拷贝漫画"
    override val baseUrl = "https://www.mangacopy.com"

    private val prefs: SharedPreferences? = context?.getSharedPreferences("copymanga_auth", Context.MODE_PRIVATE)

    private val apiHeaders: Map<String, String>
        get() {
            val base = mutableMapOf(
                "User-Agent" to UA,
                "Referer"    to "$baseUrl/",
                "platform"   to "1",
                "region"     to "1",
                "version"    to "2.2.0"
            )
            // Inject auth token if available
            val token = getToken()
            if (token.isNotEmpty()) {
                base["authorization"] = "Token $token"
            }
            return base
        }

    /** API engine — tries primary then mirror. */
    private val api: HttpEngine
        get() = HttpEngine(
            client  = client,
            mirrors = listOf("https://api.mangacopy.com/api/v3", "https://api.copy20.com/api/v3"),
            headers = apiHeaders
        )

    /** Web engine — for scraping pass JS when needed. */
    private val web: HttpEngine
        get() = HttpEngine(
            client  = client,
            mirrors = listOf("https://www.mangacopy.com", "https://copymanga.site", "https://copymanga.org"),
            headers = apiHeaders
        )

    private val s3Base = "https://s3.mangafunb.fun"

    // ─── Authentication ─────────────────────────────────────────────────────

    private fun getToken(): String {
        return cachedToken
            ?: prefs?.getString("token", "")?.also { if (it.isNotEmpty()) cachedToken = it }
            ?: ""
    }

    private fun saveToken(token: String) {
        cachedToken = token
        prefs?.edit()?.putString("token", token)?.apply()
    }

    /**
     * Login to CopyManga and store the token.
     * Call this once on first use; token persists across app restarts.
     */
    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("salt", System.currentTimeMillis().toString())
                .build()

            val req = Request.Builder()
                .url("https://api.mangacopy.com/api/v3/login")
                .post(body)
                .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
                .build()

            val res = client.newCall(req).execute()
            val json = JSONObject(res.body!!.string())
            val code = json.optInt("code")

            if (code != 200) error(json.optString("message", "登录失败 (code=$code)"))

            val token = json.getJSONObject("results").getString("token")
            saveToken(token)
            Log.i("CopyManga", "Login successful, token cached")
            token
        }
    }

    /** Check if we have a stored token. */
    fun isLoggedIn(): Boolean = getToken().isNotEmpty()

    /** Clear stored token. */
    fun logout() {
        cachedToken = null
        prefs?.edit()?.remove("token")?.apply()
    }

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

            // Try chapter2 endpoint first (returns all pages), fallback to legacy
            val chapJson = try {
                api.fetch("/comic/$slug/chapter2/$uuid")
            } catch (_: Exception) {
                api.fetch("/comic/$slug/chapter/$uuid")
            }

            val json = JSONObject(chapJson)
            val code = json.optInt("code")
            if (code == 210) {
                // Auth required — attempt auto-login if not already logged in
                if (!isLoggedIn()) {
                    login(DEFAULT_USER, DEFAULT_PASS).getOrThrow()
                    // Retry with new token
                    val retryJson = try {
                        api.fetch("/comic/$slug/chapter2/$uuid")
                    } catch (_: Exception) {
                        api.fetch("/comic/$slug/chapter/$uuid")
                    }
                    return@runCatching parseChapterImages(JSONObject(retryJson), slug, uuid)
                }
                error(json.optString("message", "需要登录 (210)"))
            }

            parseChapterImages(json, slug, uuid)
        }
    }

    private fun parseChapterImages(json: JSONObject, slug: String, uuid: String): List<String> {
        val results  = json.getJSONObject("results")
        val chapObj  = results.getJSONObject("chapter")
        val contents = chapObj.optJSONArray("contents")
        val words    = chapObj.optJSONArray("words")

        val rawUrls = extractImageUrls(chapObj, results, contents, slug, uuid)

        // Apply words reordering if present
        if (words != null && words.length() == rawUrls.size && words.length() > 0) {
            val ordered = Array(rawUrls.size) { "" }
            for (i in rawUrls.indices) {
                val pageIndex = words.getInt(i)
                if (pageIndex in ordered.indices) {
                    ordered[pageIndex] = rawUrls[i]
                }
            }
            return ordered.filter { it.isNotEmpty() }
        }
        return rawUrls
    }

    private fun extractImageUrls(
        chapObj: JSONObject, results: JSONObject,
        contents: org.json.JSONArray?, slug: String, uuid: String
    ): List<String> {
        val firstUrl = contents?.optJSONObject(0)?.optString("url", "") ?: ""

        if (firstUrl.startsWith("http")) {
            return (0 until contents!!.length()).map { contents.getJSONObject(it).getString("url") }
        }

        // Encrypted mode
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

        if (firstUrl.matches(Regex("[0-9a-fA-F]{33,}"))) {
            return (0 until contents!!.length()).map {
                decrypt(contents.getJSONObject(it).getString("url"), aesKey)
            }
        }

        val contentKey = chapObj.optString("content_key").ifEmpty { null }
                       ?: chapObj.optString("contentKey").ifEmpty { null }
        if (contentKey != null && contentKey.length > 32) {
            val arr = JSONObject(decrypt(contentKey, aesKey)).optJSONArray("contents")
                ?: error("Unexpected decrypted JSON")
            return (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotEmpty() }
            }
        }

        if (contents != null && contents.length() > 0) {
            return (0 until contents.length()).map { contents.getJSONObject(it).getString("url") }
        }
        error("No content found in chapter API response")
    }

    override fun getHeaders(): Map<String, String> = apiHeaders

    // ─── Pass JS Discovery ──────────────────────────────────────────────────

    private fun findPassJsUrl(slug: String, uuid: String): String? {
        val html = web.fetch("/comic/$slug/chapter/$uuid")

        Regex("""(https://[^\s"'<>]+comic_content_pass[^\s"'<>]+\.js)""")
            .find(html)?.groupValues?.get(1)?.let { return it }

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

        // Default credentials for auto-login when 210 is returned
        private const val DEFAULT_USER = "Kousoyu"
        private const val DEFAULT_PASS = "Xiaomu070904"

        // In-memory token cache
        private var cachedToken: String? = null
    }
}
