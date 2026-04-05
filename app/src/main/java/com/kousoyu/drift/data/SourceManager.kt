package com.kousoyu.drift.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import com.kousoyu.drift.data.sources.ManhuaguiSource
import com.kousoyu.drift.data.sources.CopyMangaSource
import com.kousoyu.drift.data.sources.BaoziNativeSource

object SourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ── Native plugins (hardcoded — they use custom algorithms or scraping) ───
    // ManhuaguiSource   : LZString + JS unpacking for chapter images
    // CopyMangaSource   : JSON API + AES-CBC decryption (self-healing key)
    // BaoziNativeSource  : HTML scraping with mirror fallback + CDN images
    private val manhuaguiSource = ManhuaguiSource(client)
    private val copyMangaSource = CopyMangaSource(client)
    private val baoziSource     = BaoziNativeSource(client)

    // ── The virtual aggregate source is always present ────────────────────────
    private val aggregateSource = AggregateSource()

    // ── Full source list: aggregate first, then native, then dynamic (OTA) ────
    var sources: List<MangaSource> = listOf(aggregateSource, manhuaguiSource, copyMangaSource, baoziSource)
        private set

    val currentSource = MutableStateFlow<MangaSource>(sources.first())

    /**
     * Called once on app launch (e.g. from Application.onCreate or MainActivity).
     * Fetches the remote rules/sources.json and registers all enabled dynamic sources.
     * Falls back silently to cached / bundled rules if network is unavailable.
     */
    suspend fun initialize(context: Context) {
        val dynamicSources = OtaManager.fetchRemoteSources(context, client)
        val newList = mutableListOf<MangaSource>(aggregateSource, manhuaguiSource, copyMangaSource, baoziSource)
        // Only add dynamic sources that don't duplicate a native source name
        val nativeNames = newList.map { it.name }.toSet()
        newList.addAll(dynamicSources.filter { it.name !in nativeNames })
        sources = newList

        // Restore current source if it still exists; otherwise default to aggregate
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = aggregateSource
        }
    }

    fun getSourceByName(name: String): MangaSource =
        sources.find { it.name == name } ?: sources.first()

    /** Legacy entry point kept for compatibility (e.g. OTA from within the app). */
    fun updateSources(remote: List<MangaSource>) {
        val newSources = mutableListOf<MangaSource>(aggregateSource, manhuaguiSource, copyMangaSource, baoziSource)
        val nativeNames = newSources.map { it.name }.toSet()
        newSources.addAll(remote.filter { it.name !in nativeNames })
        sources = newSources
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = aggregateSource
        }
    }
}

