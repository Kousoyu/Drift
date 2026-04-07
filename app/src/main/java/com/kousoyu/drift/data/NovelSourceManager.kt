package com.kousoyu.drift.data

import android.content.Context
import com.kousoyu.drift.data.sources.LinovelibSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central registry for all novel sources.
 * Parallel to [SourceManager] for manga.
 *
 * Architecture: Pure static registry. Every source is a hardcoded native plugin.
 */
object NovelSourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // Novel pages can be large
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private lateinit var linovelib: LinovelibSource

    lateinit var sources: List<NovelSource>
        private set

    private val _currentSource = MutableStateFlow<NovelSource>(PlaceholderNovelSource)
    val currentSource: StateFlow<NovelSource> = _currentSource

    private var initialized = false

    /**
     * Initialize sources with application context.
     * Must be called once from Application.onCreate() or MainActivity.
     */
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext

        linovelib = LinovelibSource(client, appCtx)
        sources   = listOf(linovelib)
        _currentSource.value = linovelib

        initialized = true
    }

    fun switchSource(source: NovelSource) {
        _currentSource.value = source
    }

    fun getSourceByName(name: String): NovelSource =
        if (initialized) sources.find { it.name == name } ?: sources.first()
        else PlaceholderNovelSource

    /** Placeholder until init() is called. */
    private object PlaceholderNovelSource : NovelSource {
        override val name = "加载中..."
        override val baseUrl = ""
        override suspend fun getPopularNovels() = Result.success(emptyList<NovelItem>())
        override suspend fun searchNovel(query: String) = Result.success(emptyList<NovelItem>())
        override suspend fun getNovelDetail(detailUrl: String) = Result.failure<NovelDetail>(Exception("未初始化"))
        override suspend fun getChapterContent(chapterUrl: String) = Result.failure<String>(Exception("未初始化"))
    }
}
