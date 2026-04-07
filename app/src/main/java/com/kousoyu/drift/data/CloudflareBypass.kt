package com.kousoyu.drift.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloudflare bypass via Android WebView — fetches fully rendered HTML.
 *
 * Optimizations:
 *   - WebView is pre-warmed and reused (not created per request)
 *   - HTML cache avoids redundant fetches within a session
 *   - Images blocked for faster page loads
 *   - Challenge detection skips extraction until CF is passed
 */
class CloudflareBypass(private val context: Context) {

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // ── Reusable WebView — created once, reused across fetches ──
    private var webView: WebView? = null
    private var isWarmedUp = false

    // ── Simple in-memory cache — avoids re-fetching during a session ──
    private val htmlCache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes
    private val MAX_CACHE = 20

    private data class CacheEntry(val html: String, val timestamp: Long)

    /**
     * Pre-warm the WebView by loading the base URL.
     * Call this early (e.g. on app start) so Cloudflare is solved
     * before the user opens the novel tab.
     */
    suspend fun preWarm(baseUrl: String) {
        if (isWarmedUp) return
        withContext(Dispatchers.Main) {
            ensureWebView()
            val deferred = CompletableDeferred<Unit>()
            webView!!.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isChallengePage(view.title ?: "")) {
                        isWarmedUp = true
                        if (!deferred.isCompleted) deferred.complete(Unit)
                    }
                }
            }
            webView!!.loadUrl(baseUrl)
            withTimeoutOrNull(20_000L) { deferred.await() }
        }
    }

    /**
     * Fetch fully rendered HTML of [url].
     * Uses cache if available, otherwise loads via WebView.
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 25_000L): String {
        // Check cache first
        val cached = htmlCache[url]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.html
        }

        val html = withContext(Dispatchers.Main) {
            ensureWebView()
            val deferred = CompletableDeferred<String>()
            var settled = false

            webView!!.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    if (isChallengePage(view.title ?: "")) return
                    if (settled) return
                    settled = true

                    view.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})()"
                    ) { rawJs ->
                        val result = unescapeJs(rawJs)
                        if (!deferred.isCompleted) deferred.complete(result)
                    }
                }
            }

            webView!!.loadUrl(url)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw Exception("页面加载超时")
        }

        // Store in cache
        if (html.length > 100) {
            if (htmlCache.size >= MAX_CACHE) {
                htmlCache.remove(htmlCache.keys.first())
            }
            htmlCache[url] = CacheEntry(html, System.currentTimeMillis())
        }

        return html
    }

    /**
     * Invalidate cache for a specific URL or all.
     */
    fun clearCache(url: String? = null) {
        if (url != null) htmlCache.remove(url) else htmlCache.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView == null) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = UA
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.blockNetworkImage = true  // Speed: skip images
            }
        }
    }

    private fun isChallengePage(title: String): Boolean {
        return "Just a moment" in title ||
               "请稍候" in title ||
               "Checking" in title ||
               "Attention Required" in title
    }

    private fun unescapeJs(raw: String): String {
        if (raw == "null" || raw.isBlank()) return ""
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\u003C", "<").replace("\\u003c", "<")
            .replace("\\u003E", ">").replace("\\u003e", ">")
            .replace("\\u0026", "&")
            .replace("\\\\", "\\")
    }
}
