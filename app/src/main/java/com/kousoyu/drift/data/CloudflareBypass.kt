package com.kousoyu.drift.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloudflare bypass via Android WebView.
 *
 * Design: creates a fresh WebView per request (reliable),
 * but caches results to avoid redundant fetches.
 * A Mutex serializes requests to prevent resource contention.
 */
class CloudflareBypass(private val context: Context) {

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // Serialize WebView requests — only one at a time
    private val mutex = Mutex()

    // In-memory HTML cache
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private val CACHE_TTL = 5 * 60 * 1000L  // 5 min
    private val MAX_CACHE = 20

    private data class CacheEntry(val html: String, val time: Long)

    /**
     * Fetch rendered HTML. Uses cache if fresh, otherwise loads via WebView.
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 30_000L): String {
        // Cache hit
        cache[url]?.let { entry ->
            if (System.currentTimeMillis() - entry.time < CACHE_TTL) return entry.html
        }

        // Serialize WebView operations
        val html = mutex.withLock {
            // Double-check cache after acquiring lock
            cache[url]?.let { entry ->
                if (System.currentTimeMillis() - entry.time < CACHE_TTL) return@withLock entry.html
            }

            withContext(Dispatchers.Main) {
                loadViaWebView(url, timeoutMs)
            }
        }

        // Validate — don't cache Cloudflare challenge pages
        if (isCloudflareHtml(html)) {
            throw Exception("Cloudflare 验证未通过，请稍后重试")
        }

        // Cache valid HTML
        if (html.length > 200) {
            if (cache.size >= MAX_CACHE) cache.remove(cache.keys.first())
            cache[url] = CacheEntry(html, System.currentTimeMillis())
        }

        return html
    }

    fun clearCache() = cache.clear()

    // ─── Internal ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadViaWebView(url: String, timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = UA
            settings.blockNetworkImage = true  // Speed: skip images
        }

        var settled = false

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                val title = view.title ?: ""

                // Still on Cloudflare challenge — wait for auto-solve
                if (isChallengePage(title)) return
                if (settled) return
                settled = true

                view.evaluateJavascript(
                    "(function(){return document.documentElement.outerHTML;})()"
                ) { raw ->
                    if (!deferred.isCompleted) {
                        deferred.complete(unescapeJs(raw))
                    }
                }
            }
        }

        wv.loadUrl(url)

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }

        wv.stopLoading()
        wv.destroy()

        return result ?: throw Exception("加载超时 (${timeoutMs / 1000}s) — 可能需要重试")
    }

    private fun isChallengePage(title: String): Boolean =
        "Just a moment" in title || "请稍候" in title ||
        "Checking" in title || "Attention Required" in title

    private fun isCloudflareHtml(html: String): Boolean =
        html.length < 500 || "cf-challenge" in html ||
        ("Just a moment" in html && "challenge-platform" in html)

    private fun unescapeJs(raw: String): String {
        if (raw == "null" || raw.isBlank()) return ""
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\u003C", "<").replace("\\u003c", "<")
            .replace("\\u003E", ">").replace("\\u003e", ">")
            .replace("\\u0026", "&")
            .replace("\\\\", "\\")
    }
}
