package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManwaSource : MangaSource {
    override val name = "漫蛙"
    override val baseUrl = "https://www.manwa.com" // typical placeholder

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("漫蛙节点建立验证失败，该次元壁极厚，模块仍在逆向破解中。")
            }
        }

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("漫蛙节点建立验证失败，该次元壁极厚，模块仍在逆向破解中。")
            }
        }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("未能在该节点解析内容。")
            }
        }

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("未能在该节点解析内容。")
            }
        }
}
