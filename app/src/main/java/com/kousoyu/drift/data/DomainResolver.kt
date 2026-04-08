package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Discovers the currently active bilinovel domain.
 *
 * Bilinovel changes domains every 1-2 years. This resolver probes
 * a list of known candidates and caches the first responsive one.
 *
 * Cost: one HEAD request (~50ms) per candidate, runs once per app launch.
 */
object DomainResolver {

    private val CANDIDATES = listOf(
        "www.bilinovel.com",
        "www.linovelib.com",
        "tw.linovelib.com",
        "w.linovelib.com",
    )

    @Volatile private var resolved: String? = null

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Returns the active domain. Cached after first successful probe.
     */
    suspend fun get(): String {
        resolved?.let { return it }
        return resolve().also { resolved = it }
    }

    /**
     * Force re-probe (e.g. after network error).
     */
    suspend fun refresh(): String = resolve().also { resolved = it }

    private suspend fun resolve(): String = withContext(Dispatchers.IO) {
        for (domain in CANDIDATES) {
            try {
                val req = Request.Builder()
                    .url("https://$domain/")
                    .head()
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = probeClient.newCall(req).execute()
                if (resp.isSuccessful || resp.code in 301..399) {
                    resp.close()
                    return@withContext domain
                }
                resp.close()
            } catch (_: Exception) { /* next candidate */ }
        }
        CANDIDATES.first() // fallback
    }
}
