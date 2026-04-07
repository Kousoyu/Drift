package com.kousoyu.drift.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloudflare bypass via Android WebView — fetches fully rendered HTML.
 *
 * Strategy:
 *   1. Load URL in an invisible WebView (real browser engine)
 *   2. WebView solves Cloudflare JS challenge automatically
 *   3. Extract rendered HTML via JavaScript after page loads
 *   4. Return clean HTML for Jsoup parsing
 *
 * This is simpler and more reliable than cookie extraction:
 * - No need to coordinate cookies between WebView and OkHttp
 * - Works even if Cloudflare uses Turnstile
 * - HTML is fully rendered (JS-generated content included)
 */
class CloudflareBypass(private val context: Context) {

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Fetch the fully rendered HTML of [url] via WebView.
     * Automatically solves Cloudflare JS challenges.
     *
     * @param url Full URL to load
     * @param timeoutMs Maximum wait time (default 25s, enough for CF challenge)
     * @return Rendered HTML string
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 25_000L): String {
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            val webView = createWebView()
            var settled = false

            // Track page loads — Cloudflare may cause multiple redirects
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)

                    // Check if still on Cloudflare challenge
                    val title = view.title ?: ""
                    if (isChallengePage(title)) {
                        // Still solving — wait for next onPageFinished
                        return
                    }

                    // Challenge passed! Extract HTML
                    if (!settled) {
                        view.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })()"
                        ) { rawJs ->
                            if (!settled) {
                                settled = true
                                // JavaScript returns it as a JSON string (quoted)
                                val html = unescapeJs(rawJs)
                                deferred.complete(html)
                            }
                        }
                    }
                }
            }

            webView.loadUrl(url)

            // Wait with timeout
            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            // Cleanup
            webView.stopLoading()
            webView.destroy()

            result ?: throw Exception("页面加载超时 (${timeoutMs / 1000}s)")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = UA
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Block images for faster loading
            settings.blockNetworkImage = true
        }
    }

    private fun isChallengePage(title: String): Boolean {
        return "Just a moment" in title ||
               "请稍候" in title ||
               "Checking" in title ||
               "Attention Required" in title ||
               "Security check" in title
    }

    /**
     * Unescape JavaScript string returned by evaluateJavascript.
     * It comes back as a JSON-encoded string: "\"<html>...<\\/html>\""
     */
    private fun unescapeJs(raw: String): String {
        if (raw == "null" || raw.isBlank()) return ""
        // Remove surrounding quotes
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        // Unescape common sequences
        return s.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\u003C", "<")
            .replace("\\u003c", "<")
            .replace("\\u003E", ">")
            .replace("\\u003e", ">")
            .replace("\\u0026", "&")
            .replace("\\\\", "\\")
    }
}
