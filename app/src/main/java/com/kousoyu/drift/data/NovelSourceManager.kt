package com.kousoyu.drift.data

import android.content.Context
import com.kousoyu.drift.data.sources.BiqugeSource
import com.kousoyu.drift.data.sources.LinovelibSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central registry for all novel sources.
 *
 * Default: BiqugeSource (no Cloudflare, instant loading)
 * Secondary: LinovelibSource (has CF, slower but light novel focused)
 */
object NovelSourceManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private lateinit var biquge: BiqugeSource
    private lateinit var linovelib: LinovelibSource

    lateinit var sources: List<NovelSource>
        private set

    private val _currentSource = MutableStateFlow<NovelSource>(PlaceholderNovelSource)
    val currentSource: StateFlow<NovelSource> = _currentSource

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext

        biquge   = BiqugeSource(client)
        linovelib = LinovelibSource(client, appCtx)

        // Biquge first — it's fast (no Cloudflare)
        sources = listOf(biquge, linovelib)
        _currentSource.value = biquge

        initialized = true
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
