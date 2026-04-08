package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Discovers the currently active bilinovel domain.
 *
 * Bilinovel changes domains every 1-2 years. This resolver probes
 * ALL candidates in parallel and picks the first responsive one.
 *
 * Cost: one HEAD request per candidate (~50ms total since parallel).
 */
object DomainResolver {

    private val CANDIDATES = listOf(
        "www.bilinovel.com",
        "www.linovelib.com",
        "tw.linovelib.com",
        "w.linovelib.com",
    )

    @Volatile private var resolved: String? = null

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

    /**
     * Probe ALL candidates in parallel, return the fastest responding one.
     */
    private suspend fun resolve(): String = withContext(Dispatchers.IO) {
        val client = DriftHttpClient.get()

        // Launch all probes in parallel
        val results = coroutineScope {
            CANDIDATES.map { domain ->
                async {
                    try {
                        val req = Request.Builder()
                            .url("https://$domain/")
                            .head()
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        val resp = client.newCall(req).execute()
                        val ok = resp.isSuccessful || resp.code in 301..399
                        resp.close()
                        if (ok) domain else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        // Return first successful (in CANDIDATES order for preference)
        for ((i, deferred) in results.withIndex()) {
            val result = deferred.await()
            if (result != null) return@withContext result
        }
        CANDIDATES.first() // fallback
    }
}
