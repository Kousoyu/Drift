package com.kousoyu.drift.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import com.kousoyu.drift.data.sources.ManhuaguiSource
import com.kousoyu.drift.data.sources.CopyMangaSource
import com.kousoyu.drift.data.sources.BaoziNativeSource

/**
 * Central registry of all manga sources.
 *
 * Architecture: Pure static. No network calls, no OTA, no dynamic loading.
 * Every source is a hardcoded native plugin — this guarantees stability.
 *
 * Call [init] once from Application.onCreate() to provide Context.
 */
object SourceManager {

    // Uses shared DriftHttpClient for connection pool reuse + disk cache

    // Sources are lazily initialized after init() provides the context
    private lateinit var copyManga: CopyMangaSource
    private lateinit var baozi: BaoziNativeSource
    private lateinit var manhuagui: ManhuaguiSource
    private lateinit var aggregate: AggregateSource

    lateinit var sources: List<MangaSource>
        private set
    val currentSource = MutableStateFlow<MangaSource>(PlaceholderSource)

    private var initialized = false

    /**
     * Initialize sources with application context.
     * Must be called once from Application.onCreate() or MainActivity.
     */
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext
        val client = DriftHttpClient.get(appCtx)
        copyManga  = CopyMangaSource(client, appCtx)
        baozi      = BaoziNativeSource(client)
        manhuagui  = ManhuaguiSource(client)
        aggregate  = AggregateSource()
        sources    = listOf(aggregate, copyManga, baozi, manhuagui)
        currentSource.value = aggregate
        initialized = true
    }

    fun getSourceByName(name: String): MangaSource =
        if (initialized) sources.find { it.name == name } ?: aggregate
        else PlaceholderSource

    /** Placeholder until init() is called. Should never be used in practice. */
    private object PlaceholderSource : MangaSource {
        override val name = "加载中..."
        override val baseUrl = ""
        override suspend fun getPopularManga() = Result.success(emptyList<Manga>())
        override suspend fun searchManga(query: String) = Result.success(emptyList<Manga>())
        override suspend fun getMangaDetail(detailUrl: String) = Result.failure<MangaDetail>(Exception("未初始化"))
        override suspend fun getChapterImages(chapterUrl: String) = Result.failure<List<String>>(Exception("未初始化"))
        override fun getHeaders() = emptyMap<String, String>()
    }
}
