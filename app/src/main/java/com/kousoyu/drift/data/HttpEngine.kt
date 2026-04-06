package com.kousoyu.drift.data

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared HTTP engine with automatic mirror fallback.
 *
 * Every native source delegates HTTP calls to an HttpEngine instance.
 * If the primary domain fails, the engine automatically tries the next mirror.
 *
 * Usage:
 *   val engine = HttpEngine(client, listOf("https://primary.com", "https://mirror.com"), headers)
 *   val html = engine.fetch("/path")          // tries mirrors in order
 *   val json = engine.fetchDirect("https://api.example.com/data")  // single URL, no fallback
 */
class HttpEngine(
    private val client: OkHttpClient,
    val mirrors: List<String>,
    private val headers: Map<String, String> = emptyMap()
) {
    /** Fetch a single absolute URL. No mirror fallback. */
    fun fetchDirect(url: String, extra: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            (headers + extra).forEach { (k, v) -> header(k, v) }
        }.build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code} @ $url")
        return res.body!!.string()
    }

    /**
     * Fetch a relative [path] by trying each mirror in order.
     * If [path] is already an absolute URL, its domain is swapped to the current mirror.
     */
    fun fetch(path: String, extra: Map<String, String> = emptyMap()): String {
        var lastError: Throwable = IllegalStateException("No mirrors configured")
        for (base in mirrors) {
            val url = resolve(base, path)
            try { return fetchDirect(url, extra) } catch (e: Exception) { lastError = e }
        }
        throw lastError
    }

    /** Build absolute URL from base + path, handling both relative and absolute paths. */
    private fun resolve(base: String, path: String): String {
        if (!path.startsWith("http")) {
            return "${base.trimEnd('/')}/${path.trimStart('/')}"
        }
        // Absolute URL → swap domain to current mirror
        val afterScheme = path.substringAfter("://")
        val slashIdx = afterScheme.indexOf('/')
        return if (slashIdx > 0) "${base.trimEnd('/')}${afterScheme.substring(slashIdx)}" else path
    }
}
