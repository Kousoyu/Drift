package com.kousoyu.drift.data

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import com.kousoyu.drift.data.sources.ManhuaguiSource

object SourceManager {
    private val client = OkHttpClient()
    val nativeSources = listOf(ManhuaguiSource(client))

    // 0 — 聚合 (全网) — virtual, fan-out to all below
    var sources: List<MangaSource> = listOf(AggregateSource()) + nativeSources

    val currentSource = MutableStateFlow<MangaSource>(sources.first())

    fun getSourceByName(name: String): MangaSource {
        return sources.find { it.name == name } ?: sources.first()
    }

    fun updateSources(remote: List<MangaSource>) {
        val newSources = mutableListOf<MangaSource>(AggregateSource())
        // add hardcoded native sources first
        newSources.addAll(nativeSources)
        // remote sources (loaded via OTA JSON) appended directly
        newSources.addAll(remote)

        sources = newSources
        
        // Update current source if it was removed
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = sources.first()
        }
    }
}
