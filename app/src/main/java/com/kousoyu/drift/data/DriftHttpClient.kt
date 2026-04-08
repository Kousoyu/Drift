package com.kousoyu.drift.data

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for the entire app.
 *
 * Why shared?
 *  - HTTP/2 connection reuse across all sources (manga + novel)
 *  - Single connection pool → fewer sockets, faster warm starts
 *  - Disk cache → repeat visits load from local storage
 *
 * Performance tuning:
 *  - 15 idle connections (default 5 is too few for parallel loads)
 *  - 10MB HTTP disk cache (catalog pages, detail pages)
 *  - 8s connect / 10s read (aggressive but reasonable for mobile)
 */
object DriftHttpClient {

    @Volatile
    private var instance: OkHttpClient? = null

    fun get(context: Context? = null): OkHttpClient {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val builder = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(15, 3, TimeUnit.MINUTES))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)

            // Add disk cache if context is available
            if (context != null) {
                val cacheDir = File(context.cacheDir, "http_cache")
                builder.cache(Cache(cacheDir, 10L * 1024 * 1024)) // 10MB
            }

            return builder.build().also { instance = it }
        }
    }
}
