package com.kousoyu.drift.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import com.kousoyu.drift.data.sources.ManhuaguiSource

object SourceManager {

    private val client = OkHttpClient()

    // ── ManhuaguiSource is the only "native" (hardcoded) plugin ──────────────
    // It stays native because it needs JS unpacking / LZString decryption that
    // cannot be expressed in a simple CSS/JSON rule.
    private val manhuaguiSource = ManhuaguiSource(client)

    // ── The virtual aggregate source is always present ────────────────────────
    private val aggregateSource = AggregateSource()

    // ── Full source list: aggregate first, then native, then dynamic (OTA) ────
    var sources: List<MangaSource> = listOf(aggregateSource, manhuaguiSource)
        private set

    val currentSource = MutableStateFlow<MangaSource>(sources.first())

    /**
     * Called once on app launch (e.g. from Application.onCreate or MainActivity).
     * Fetches the remote rules/sources.json and registers all enabled dynamic sources.
     * Falls back silently to cached / bundled rules if network is unavailable.
     */
    suspend fun initialize(context: Context) {
        val dynamicSources = OtaManager.fetchRemoteSources(context, client)
        val newList = mutableListOf<MangaSource>(aggregateSource, manhuaguiSource)
        newList.addAll(dynamicSources)
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
        val newSources = mutableListOf<MangaSource>(aggregateSource, manhuaguiSource)
        newSources.addAll(remote)
        sources = newSources
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = aggregateSource
        }
    }
}

