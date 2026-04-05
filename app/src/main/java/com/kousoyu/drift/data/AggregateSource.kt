package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class AggregateSource : MangaSource {
    override val name = "聚合 (全网)"
    override val baseUrl = ""

    private val targetSources get() = SourceManager.sources.filter { it.name != this.name }

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = targetSources.map { source ->
                    async { 
                        kotlinx.coroutines.withTimeoutOrNull(5000L) {
                            source.getPopularManga().getOrNull()
                        } ?: emptyList()
                    }
                }.awaitAll()
                
                // Mix results: [S1-1, S2-1, S3-1, S1-2, S2-2, ...]
                val mixed = mutableListOf<Manga>()
                val maxLen = results.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxLen) {
                    for (list in results) {
                        if (i < list.size) mixed.add(list[i])
                    }
                }
                mixed.distinctBy { it.detailUrl }
            }
        }

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                 val results = targetSources.map { source ->
                    async { 
                        kotlinx.coroutines.withTimeoutOrNull(5000L) {
                            source.searchManga(query).getOrNull()
                        } ?: emptyList()
                    }
                }.awaitAll()
                
                // Mix results similarly
                val mixed = mutableListOf<Manga>()
                val maxLen = results.maxOfOrNull { it.size } ?: 0
                for (i in 0 until maxLen) {
                    for (list in results) {
                        if (i < list.size) mixed.add(list[i])
                    }
                }
                mixed.distinctBy { it.detailUrl }
            }
        }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> {
        return Result.failure(Exception("聚合源为虚拟源，不可直接提取详情。"))
    }

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> {
        return Result.failure(Exception("聚合源为虚拟源，不可直接读取图片。"))
    }
}
