package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AggregateSource : MangaSource {
    override val name = "聚合 (全网)"
    override val baseUrl = ""

    private val targetSources get() = SourceManager.sources.filter { it.name != this.name }

    // ─── Streaming (recommended) ──────────────────────────────────────────────
    // Emits partial results the moment each individual source completes.
    // The UI sees the first batch in ~0.5–1 s and subsequent sources are appended live.

    /**
     * Returns a [Flow] that emits the growing cumulative manga list every time
     * a new source finishes.  Each emission is a full, de-duplicated list so
     * the UI can simply replace its state with whatever it receives.
     */
    fun getPopularMangaFlow(): Flow<List<Manga>> = channelFlow {
        val accumulated = mutableListOf<Manga>()
        val sources = targetSources

        sources.map { source ->
            launch(Dispatchers.IO) {
                val items = withTimeoutOrNull(3000L) {
                    source.getPopularManga().getOrNull()
                } ?: return@launch
                synchronized(accumulated) { accumulated.addAll(items) }
                // Emit snapshot: interleave so diverse sources alternate
                send(accumulated.distinctBy { it.detailUrl }.shuffled())
            }
        }.forEach { /* jobs started above */ _ -> }
    }

    fun searchMangaFlow(query: String): Flow<List<Manga>> = channelFlow {
        val accumulated = mutableListOf<Manga>()
        targetSources.map { source ->
            launch(Dispatchers.IO) {
                val items = withTimeoutOrNull(3000L) {
                    source.searchManga(query).getOrNull()
                } ?: return@launch
                synchronized(accumulated) { accumulated.addAll(items) }
                send(accumulated.distinctBy { it.detailUrl })
            }
        }.forEach { _ -> }
    }

    // ─── Blocking fallback (kept for backward compat) ─────────────────────────

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = targetSources.map { source ->
                    async {
                        withTimeoutOrNull(2500L) {
                            source.getPopularManga().getOrNull()
                        } ?: emptyList()
                    }
                }.awaitAll()
                interleave(results).distinctBy { it.detailUrl }
            }
        }

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = targetSources.map { source ->
                    async {
                        withTimeoutOrNull(2500L) {
                            source.searchManga(query).getOrNull()
                        } ?: emptyList()
                    }
                }.awaitAll()
                interleave(results).distinctBy { it.detailUrl }
            }
        }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        Result.failure(Exception("聚合源为虚拟源，不可直接提取详情。"))

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        Result.failure(Exception("聚合源为虚拟源，不可直接读取图片。"))

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Interleaves multiple lists: [S1[0], S2[0], S3[0], S1[1], S2[1], …] */
    private fun interleave(lists: List<List<Manga>>): List<Manga> {
        val out = mutableListOf<Manga>()
        val max = lists.maxOfOrNull { it.size } ?: return out
        for (i in 0 until max) {
            for (list in lists) {
                if (i < list.size) out.add(list[i])
            }
        }
        return out
    }
}

