package com.kousoyu.drift.data

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for the entire app.
 *
 * Performance tuning:
 *  - 15 idle connections, 5 min keep-alive (HTTP/2 connection reuse)
 *  - 10MB HTTP disk cache
 *  - DNS cache (avoid repeated DNS lookups)
 *  - Preconnect: warm up TCP+TLS to source domains on app start
 */
object DriftHttpClient {

    @Volatile
    private var instance: OkHttpClient? = null

    // ── DNS cache ──────────────────────────────────────────────────────────
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val cachedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return dnsCache.getOrPut(hostname) { Dns.SYSTEM.lookup(hostname) }
        }
    }

    fun get(context: Context? = null): OkHttpClient {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val builder = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
                .dns(cachedDns)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)

            if (context != null) {
                val cacheDir = File(context.cacheDir, "http_cache")
                builder.cache(Cache(cacheDir, 10L * 1024 * 1024))
            }

            return builder.build().also { instance = it }
        }
    }

    /**
     * Preconnect to source domains — warm up DNS + TCP + TLS.
     * Called once on app start from a background thread.
     * After this, first real request to each domain is ~0ms connect time.
     */
    fun preconnect() {
        val domains = listOf(
            "www.bilinovel.com",
            "www.linovelib.com",
            "cn.webmota.com",
            "www.mangacopy.com",
            "api.mangacopy.com",
            "tw.manhuagui.com",
        )
        val client = get()
        for (domain in domains) {
            try {
                // HEAD request: minimal data, just establishes connection
                val req = Request.Builder()
                    .url("https://$domain/")
                    .head()
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) { /* silent — preconnect is best-effort */ }
        }
    }
}
