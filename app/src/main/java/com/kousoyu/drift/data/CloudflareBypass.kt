package com.kousoyu.drift.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cloudflare bypass — hybrid strategy:
 *
 *   1st request  → WebView (solves challenge, captures cookies)  ~10s
 *   2nd+ request → OkHttp + cookies (direct HTTP, no WebView)   ~0.5s
 *   Cookie expired → auto-fallback to WebView → refresh cookies
 *
 * This gives fast subsequent loads while handling Cloudflare reliably.
 */
class CloudflareBypass(private val context: Context) {

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val mutex = Mutex()

    // Cached cookies from WebView (domain → cookie string)
    private val cookieStore = mutableMapOf<String, String>()
    private var cookieTimestamp = 0L
    private val COOKIE_TTL = 30 * 60 * 1000L  // 30 min

    // HTML cache
    private val htmlCache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private val CACHE_TTL = 5 * 60 * 1000L
    private val MAX_CACHE = 20
    private data class CacheEntry(val html: String, val time: Long)

    // OkHttp client for fast subsequent requests
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Fetch HTML — tries OkHttp first (fast), falls back to WebView if blocked.
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 30_000L): String {
        // Cache hit
        htmlCache[url]?.let { if (System.currentTimeMillis() - it.time < CACHE_TTL) return it.html }

        return mutex.withLock {
            // Double-check cache
            htmlCache[url]?.let { if (System.currentTimeMillis() - it.time < CACHE_TTL) return@withLock it.html }

            // Try fast OkHttp path first (if we have cookies)
            if (hasFreshCookies()) {
                val html = tryOkHttp(url)
                if (html != null && !isCloudflareHtml(html)) {
                    cacheHtml(url, html)
                    return@withLock html
                }
                // Cookies expired or blocked — fall through to WebView
            }

            // Slow path: WebView
            val html = withContext(Dispatchers.Main) {
                loadViaWebView(url, timeoutMs)
            }

            if (isCloudflareHtml(html)) {
                throw Exception("Cloudflare 验证未通过，请稍后重试")
            }

            // Capture cookies for fast subsequent requests
            captureCookies(url)
            cacheHtml(url, html)
            html
        }
    }

    fun clearCache() { htmlCache.clear(); cookieStore.clear(); cookieTimestamp = 0 }

    // ─── OkHttp fast path ───────────────────────────────────────────────────

    private suspend fun tryOkHttp(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val domain = java.net.URI(url).host ?: return@withContext null
            val cookies = cookieStore[domain] ?: return@withContext null

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", "${java.net.URI(url).scheme}://$domain/")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (body.length < 200) return@withContext null
            body
        } catch (e: Exception) {
            null
        }
    }

    // ─── WebView slow path ──────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadViaWebView(url: String, timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = UA
            settings.blockNetworkImage = true
        }

        var settled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                if (isChallengePage(view.title ?: "")) return
                if (settled) return
                settled = true
                view.evaluateJavascript(
                    "(function(){return document.documentElement.outerHTML;})()"
                ) { raw ->
                    if (!deferred.isCompleted) deferred.complete(unescapeJs(raw))
                }
            }
        }

        wv.loadUrl(url)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        wv.stopLoading()
        wv.destroy()
        return result ?: throw Exception("加载超时")
    }

    // ─── Cookie management ──────────────────────────────────────────────────

    private fun captureCookies(url: String) {
        try {
            val domain = java.net.URI(url).host ?: return
            val cm = CookieManager.getInstance()
            val cookies = cm.getCookie(url)
            if (!cookies.isNullOrBlank()) {
                cookieStore[domain] = cookies
                cookieTimestamp = System.currentTimeMillis()
            }
        } catch (_: Exception) { }
    }

    private fun hasFreshCookies(): Boolean =
        cookieStore.isNotEmpty() && System.currentTimeMillis() - cookieTimestamp < COOKIE_TTL

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun isChallengePage(title: String) =
        "Just a moment" in title || "请稍候" in title || "Checking" in title

    private fun isCloudflareHtml(html: String) =
        html.length < 500 || "cf-challenge" in html ||
        ("Just a moment" in html && "challenge-platform" in html)

    private fun cacheHtml(url: String, html: String) {
        if (html.length > 200) {
            if (htmlCache.size >= MAX_CACHE) htmlCache.remove(htmlCache.keys.first())
            htmlCache[url] = CacheEntry(html, System.currentTimeMillis())
        }
    }

    private fun unescapeJs(raw: String): String {
        if (raw == "null" || raw.isBlank()) return ""
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\u003C", "<").replace("\\u003c", "<")
            .replace("\\u003E", ">").replace("\\u003e", ">")
            .replace("\\u0026", "&").replace("\\\\", "\\")
    }
}
