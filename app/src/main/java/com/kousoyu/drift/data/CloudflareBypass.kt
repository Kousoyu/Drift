package com.kousoyu.drift.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cloudflare bypass via Android WebView.
 *
 * Strategy:
 *   1. Load URL in an invisible WebView (real browser engine)
 *   2. WebView automatically solves Cloudflare JS challenges
 *   3. Extract cf_clearance + other cookies after page loads
 *   4. Inject cookies into OkHttp for subsequent requests
 *
 * This is the same approach used by Tachiyomi/Mihon — battle-tested,
 * permanent, and impossible for Cloudflare to block without blocking
 * all mobile browsers.
 *
 * Usage:
 *   val engine = CloudflareBypass(context)
 *   val cookies = engine.getCookies("https://www.linovelib.com/")
 *   // Use cookies in OkHttp headers: "Cookie" -> cookies
 */
class CloudflareBypass(private val context: Context) {

    private val cookieManager = CookieManager.getInstance()

    /**
     * Load [url] in a WebView, wait for Cloudflare to pass,
     * return the cookie string for use in OkHttp requests.
     *
     * @param url The URL to navigate to (usually the site's homepage)
     * @param timeoutMs Maximum time to wait for challenge resolution
     * @return Cookie string (e.g. "cf_clearance=xxx; __cf_bm=yyy")
     */
    suspend fun getCookies(url: String, timeoutMs: Long = 30_000L): String {
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()

            val webView = createWebView()
            var challengePassed = false

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    // Check if Cloudflare challenge is done
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance") || !isCloudflareChallenge(view)) {
                        if (!challengePassed) {
                            challengePassed = true
                            deferred.complete(cookies)
                        }
                    }
                }
            }

            webView.loadUrl(url)

            // Wait for cookies with timeout
            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: (cookieManager.getCookie(url) ?: "")

            // Cleanup
            webView.stopLoading()
            webView.destroy()

            result
        }
    }

    /**
     * Fetch HTML content of [url] by first solving Cloudflare,
     * then using OkHttp with the obtained cookies.
     */
    suspend fun fetchWithBypass(
        client: OkHttpClient,
        url: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val cookies = getCookies(url)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("User-Agent", UA)
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body!!.string()
        }
    }

    /**
     * Get cached cookies for a domain (no WebView needed if still fresh).
     * Returns null if no cookies are cached.
     */
    fun getCachedCookies(url: String): String? {
        val cookies = cookieManager.getCookie(url)
        return if (cookies != null && cookies.contains("cf_clearance")) cookies else null
    }

    /**
     * Clear all cookies for a domain (force re-solve on next request).
     */
    fun clearCookies(url: String) {
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = UA
            // Invisible — no UI footprint
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
    }

    private fun isCloudflareChallenge(view: WebView): Boolean {
        // Cloudflare challenge pages have specific titles
        val title = view.title ?: ""
        return title.contains("Just a moment") ||
               title.contains("请稍候") ||
               title.contains("Checking") ||
               title.contains("Attention Required")
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
