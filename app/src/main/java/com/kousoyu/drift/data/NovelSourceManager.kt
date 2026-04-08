package com.kousoyu.drift.data

import com.kousoyu.drift.data.sources.LinovelibSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Novel source registry. Pure OkHttp — no Context needed.
 */
object NovelSourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val linovelib = LinovelibSource(client)

    val sources: List<NovelSource> = listOf(linovelib)

    private val _currentSource = MutableStateFlow<NovelSource>(linovelib)
    val currentSource: StateFlow<NovelSource> = _currentSource

    fun switchSource(source: NovelSource) { _currentSource.value = source }

    fun getSourceByName(name: String): NovelSource =
        sources.find { it.name == name } ?: sources.first()
}
