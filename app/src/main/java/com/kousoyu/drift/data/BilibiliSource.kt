package com.kousoyu.drift.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BilibiliSource : MangaSource {
    override val name = "哔哩哔哩漫画"
    override val baseUrl = "https://manga.bilibili.com"

    override suspend fun getPopularManga(): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("B站防爬御壁极高并涉及动态令牌加载，接入模块暂时封锁。")
            }
        }

    override suspend fun searchManga(query: String): Result<List<Manga>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("B站接口限制，未获取到代理访问权。")
            }
        }

    override suspend fun getMangaDetail(detailUrl: String): Result<MangaDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("内容封锁中... 敬请期待！")
            }
        }

    override suspend fun getChapterImages(chapterUrl: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                error("B站强制禁止未授权图片漫游。")
            }
        }
}
