package com.kousoyu.drift.data

import com.kousoyu.drift.data.sources.LinovelibSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Novel source registry. Uses shared [DriftHttpClient] for connection reuse.
 */
object NovelSourceManager {

    private val linovelib = LinovelibSource(DriftHttpClient.get())

    val sources: List<NovelSource> = listOf(linovelib)

    private val _currentSource = MutableStateFlow<NovelSource>(linovelib)
    val currentSource: StateFlow<NovelSource> = _currentSource

    fun switchSource(source: NovelSource) { _currentSource.value = source }

    fun getSourceByName(name: String): NovelSource =
        sources.find { it.name == name } ?: sources.first()
}
