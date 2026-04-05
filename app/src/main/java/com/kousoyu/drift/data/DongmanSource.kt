package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DongmanSource : MangaSource {
    override val name = "咚漫"
    override val baseUrl = "https://www.dongmanmanhua.cn"

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("咚漫原生驱动开发中，请切换其他源进行探索此次元。")
            }
        }

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("咚漫原生驱动开发中，请切换其他源进行探索此次元。")
            }
        }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("未捕获任何维度信息。")
            }
        }

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("加密的残片，请退出后重试。")
            }
        }
}
