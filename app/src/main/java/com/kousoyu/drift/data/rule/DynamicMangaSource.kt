package com.kousoyu.drift.data.rule

import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaChapter
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * A generic MangaSource implementation that uses a JSON-defined RuleConfig
 * and the Jsoup RuleEvaluator to scrape data, requiring zero hardcoded parsing logic.
 */
class DynamicMangaSource(
    private val config: DynamicSourceConfig,
    private val client: OkHttpClient
) : MangaSource {

    override val name: String = config.name
    override val baseUrl: String = config.baseUrl

    // ─── HTTP Utility ─────────────────────────────────────────────────────────

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer"    to config.baseUrl
    ) + config.headers  // JSON-defined headers override defaults

    /** Candidate base URLs: primary first, then mirrors */
    private val candidates = listOf(config.baseUrl) + config.mirrorUrls

    /**
     * Fetches [url] with mirror fallback.
     * If the primary base URL fails, transparently retries each mirror in order.
     */
    private fun fetch(url: String): String {
        var lastError: Throwable = IllegalStateException("No candidates")
        for (base in candidates) {
            val resolvedUrl = if (url.startsWith("http")) url
                             else "${base.removeSuffix("/")}/${url.removePrefix("/")}"
            try {
                val reqBuilder = Request.Builder().url(resolvedUrl).get()
                defaultHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
                val response = client.newCall(reqBuilder.build()).execute()
                if (response.isSuccessful) return response.body!!.string()
                lastError = IllegalStateException("HTTP ${response.code} @ $resolvedUrl")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun ensureAbsoluteUrl(extractedPath: String, imageBase: String? = null): String {
        if (extractedPath.isEmpty()) return ""
        if (extractedPath.startsWith("http")) return extractedPath
        if (extractedPath.startsWith("//")) return "https:$extractedPath"
        val base = imageBase ?: config.baseUrl
        return "${base.removeSuffix("/")}/${extractedPath.removePrefix("/")}"
    }

    // ─── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.removeSuffix("/")}/${config.searchRule.popularFormat.removePrefix("/")}"
                val html = fetch(url)
                parseList(html)
            }
        }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val urlFormat = config.searchRule.urlFormat
                val path = urlFormat.replace("{query}", encodedQuery)
                val url = "${baseUrl.removeSuffix("/")}/${path.removePrefix("/")}"

                val html = fetch(url)
                parseList(html)
            }
        }

    // ─── List Parser (reused for Popular & Search) ──────────────────────────

    private fun parseList(payload: String): List<Manga> {
        val trimmed = payload.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val elements = JsonRuleEvaluator.getList(trimmed, config.searchRule.listSelector.removePrefix("@json:"))
            return elements.mapNotNull { el ->
                var u = JsonRuleEvaluator.getString(el, config.searchRule.urlSelector.removePrefix("@json:"))
                if (u.isEmpty()) return@mapNotNull null
                
                if (config.searchRule.detailUrlPrefix != null) u = config.searchRule.detailUrlPrefix + u
                if (config.searchRule.detailUrlSuffix != null) u = u + config.searchRule.detailUrlSuffix

                val t = JsonRuleEvaluator.getString(el, config.searchRule.titleSelector.removePrefix("@json:"))
                if (t.isEmpty()) return@mapNotNull null
                
                Manga(
                    title = t,
                    coverUrl = ensureAbsoluteUrl(JsonRuleEvaluator.getString(el, config.searchRule.coverSelector.removePrefix("@json:"))),
                    detailUrl = ensureAbsoluteUrl(u),
                    latestChapter = config.searchRule.latestChapterSelector?.let { JsonRuleEvaluator.getString(el, it.removePrefix("@json:")) } ?: "",
                    genre = config.searchRule.genreSelector?.let { JsonRuleEvaluator.getString(el, it.removePrefix("@json:")) } ?: "",
                    author = config.searchRule.authorSelector?.let { JsonRuleEvaluator.getString(el, it.removePrefix("@json:")) } ?: "", 
                    sourceName = name
                )
            }.distinctBy { it.detailUrl }
        }

        val doc = Jsoup.parse(payload)
        val elements = doc.select(config.searchRule.listSelector)
        
        return elements.mapNotNull { el ->
            var u = RuleEvaluator.getString(el, config.searchRule.urlSelector)
            if (u.isEmpty()) return@mapNotNull null
            
            if (config.searchRule.detailUrlPrefix != null) u = config.searchRule.detailUrlPrefix + u
            if (config.searchRule.detailUrlSuffix != null) u = u + config.searchRule.detailUrlSuffix
            
            val t = RuleEvaluator.getString(el, config.searchRule.titleSelector)
            if (t.isEmpty()) return@mapNotNull null

            Manga(
                title = t,
                coverUrl = ensureAbsoluteUrl(RuleEvaluator.getString(el, config.searchRule.coverSelector)),
                detailUrl = ensureAbsoluteUrl(u),
                latestChapter = config.searchRule.latestChapterSelector?.let { RuleEvaluator.getString(el, it) } ?: "",
                genre = config.searchRule.genreSelector?.let { RuleEvaluator.getString(el, it) } ?: "",
                author = config.searchRule.authorSelector?.let { RuleEvaluator.getString(el, it) } ?: "",
                sourceName = name
            )
        }.distinctBy { it.detailUrl }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = config.detailRule

                // If the rule specifies a separate chapter-list URL, fetch it separately
                val chaptersFetchUrl = r.chapterListUrl
                    ?.replace("{detailUrl}", detailUrl)
                    ?: detailUrl

                val payload = fetch(detailUrl)
                val trimmed = payload.trimStart()

                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    val root = org.json.JSONObject(trimmed)
                    val title  = JsonRuleEvaluator.getString(root, r.titleSelector.removePrefix("@json:")).ifEmpty { "未知标题" }
                    val cover  = ensureAbsoluteUrl(JsonRuleEvaluator.getString(root, r.coverSelector.removePrefix("@json:")))
                    val author = JsonRuleEvaluator.getString(root, r.authorSelector.removePrefix("@json:"))
                    val desc   = JsonRuleEvaluator.getString(root, r.descSelector.removePrefix("@json:")).ifEmpty { "暂无简介" }
                    val status = JsonRuleEvaluator.getString(root, r.statusSelector.removePrefix("@json:")).ifEmpty { "未知" }

                    val chapPayload = if (chaptersFetchUrl != detailUrl) fetch(chaptersFetchUrl) else trimmed
                    val chapterNodes = JsonRuleEvaluator.getList(chapPayload, r.chapterListSelector.removePrefix("@json:"))
                    val chapters = chapterNodes.mapNotNull { node ->
                        val url  = JsonRuleEvaluator.getString(node, r.chapterUrlSelector.removePrefix("@json:"))
                        val name = JsonRuleEvaluator.getString(node, r.chapterNameSelector.removePrefix("@json:"))
                        if (url.isNotEmpty() && name.isNotEmpty()) MangaChapter(name, ensureAbsoluteUrl(url)) else null
                    }.distinctBy { it.url }.reversed()

                    return@runCatching MangaDetail(detailUrl, title, cover, author, desc, status, chapters)
                }

                val doc = Jsoup.parse(payload)
                val title  = RuleEvaluator.getString(doc, r.titleSelector).ifEmpty { "未知标题" }
                val cover  = ensureAbsoluteUrl(RuleEvaluator.getString(doc, r.coverSelector))
                val author = RuleEvaluator.getString(doc, r.authorSelector)
                val desc   = RuleEvaluator.getString(doc, r.descSelector).ifEmpty { "暂无简介" }
                val status = RuleEvaluator.getString(doc, r.statusSelector).ifEmpty { "未知" }

                // Possibly fetch chapters from a separate page
                val chapDoc = if (chaptersFetchUrl != detailUrl) Jsoup.parse(fetch(chaptersFetchUrl)) else doc
                val chapters = chapDoc.select(r.chapterListSelector).mapNotNull { node ->
                    val url  = RuleEvaluator.getString(node, r.chapterUrlSelector)
                    val name = RuleEvaluator.getString(node, r.chapterNameSelector)
                    if (url.isNotEmpty() && name.isNotEmpty()) MangaChapter(name, ensureAbsoluteUrl(url)) else null
                }.distinctBy { it.url }.reversed()

                MangaDetail(url = detailUrl, title = title, coverUrl = cover,
                    author = author, description = desc, status = status, chapters = chapters)
            }
        }

    // ─── Chapter Images ───────────────────────────────────────────────────────

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = fetch(chapterUrl)
                val trimmed = payload.trimStart()
                val r = config.chapterImagesRule

                val imgs = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    JsonRuleEvaluator.getStringList(
                        trimmed,
                        r.imageListSelector.removePrefix("@json:"),
                        r.imageUrlSelector.removePrefix("@json:")
                    )
                } else {
                    val doc = Jsoup.parse(payload)
                    RuleEvaluator.getStringList(doc, r.imageListSelector, r.imageUrlSelector)
                }

                val absoluteImgs = imgs.map { ensureAbsoluteUrl(it, r.imageBaseUrl) }

                if (absoluteImgs.isEmpty()) {
                    error("$name: 未解析到任何图片。也许网站结构已变更，请更新规则或检查 imageBaseUrl。")
                }
                absoluteImgs
            }
        }

    override fun getHeaders(): Map<String, String> = defaultHeaders
}
