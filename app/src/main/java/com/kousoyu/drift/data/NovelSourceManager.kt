package com.kousoyu.drift.data

import android.content.Context
import com.kousoyu.drift.data.sources.LinovelibSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central registry for all novel sources.
 * Pre-warms Cloudflare on init for instant novel tab loading.
 */
object NovelSourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var linovelib: LinovelibSource

    lateinit var sources: List<NovelSource>
        private set

    private val _currentSource = MutableStateFlow<NovelSource>(PlaceholderNovelSource)
    val currentSource: StateFlow<NovelSource> = _currentSource

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext

        linovelib = LinovelibSource(client, appCtx)
        sources   = listOf(linovelib)
        _currentSource.value = linovelib

        initialized = true

        // Pre-warm Cloudflare in background — user won't wait when opening novel tab
        scope.launch {
            try { linovelib.preWarm() } catch (_: Exception) { }
        }
    }

    fun switchSource(source: NovelSource) {
        _currentSource.value = source
    }

    fun getSourceByName(name: String): NovelSource =
        if (initialized) sources.find { it.name == name } ?: sources.first()
        else PlaceholderNovelSource

    private object PlaceholderNovelSource : NovelSource {
        override val name = "加载中..."
        override val baseUrl = ""
        override suspend fun getPopularNovels() = Result.success(emptyList<NovelItem>())
        override suspend fun searchNovel(query: String) = Result.success(emptyList<NovelItem>())
        override suspend fun getNovelDetail(detailUrl: String) = Result.failure<NovelDetail>(Exception("未初始化"))
        override suspend fun getChapterContent(chapterUrl: String) = Result.failure<String>(Exception("未初始化"))
    }
}

