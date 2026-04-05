package com.kousoyu.drift.data

import kotlinx.coroutines.flow.MutableStateFlow

object SourceManager {
    // 0 — 聚合 (全网) — virtual, fan-out to all below
    var sources: List<MangaSource> = listOf(AggregateSource())

    val currentSource = MutableStateFlow<MangaSource>(sources.first())

    fun getSourceByName(name: String): MangaSource {
        return sources.find { it.name == name } ?: sources.first()
    }

    fun updateSources(remote: List<MangaSource>) {
        val newSources = mutableListOf<MangaSource>(AggregateSource())
        // remote sources (loaded via OTA JSON) appended directly
        newSources.addAll(remote)

        sources = newSources
        
        // Update current source if it was removed
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = sources.first()
        }
    }
}
