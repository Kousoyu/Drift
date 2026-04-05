package com.kousoyu.drift.data

import kotlinx.coroutines.flow.MutableStateFlow
object SourceManager {
    var sources: List<MangaSource> = listOf(
        AggregateSource(),    // 0 — 聚合 (全网) — virtual, fan-out to all below
        createDynamicBaoziSource(), // 1 — 包子漫画 (纯动态规则版)
        CopyMangaSource(),    // 2 — 拷贝漫画
        ManhuaguiSource(),    // 3 — 漫画柜
        DuManWuSource(),      // 4 — 读漫屋
        JMComicSource()       // 5 — 禁漫天堂 (R18)
    )

    // ─── PoC: Dynamic JSON Source Example ────────────────────────────────────
    
    private fun createDynamicBaoziSource(): com.kousoyu.drift.data.rule.DynamicMangaSource {
        val config = com.kousoyu.drift.data.rule.DynamicSourceConfig(
            name = "包子漫画",
            baseUrl = "https://www.baozimh.com",
            searchRule = com.kousoyu.drift.data.rule.SearchRule(
                popularFormat = "/",
                urlFormat = "/search?type=all&q={query}",
                listSelector = "a.comics-card__poster",
                titleSelector = "h3@text",  // Note: we might need sibling traversal if HTML is tricky, but let's try direct or just text if it's within the 'a'
                // Actually in baozi, title is in a sibling. Let's adjust rule to target the parent div
                // The parent is div.pure-u-1-2
                // Since this is just a PoC, let's target the exact list item
                coverSelector = "amp-img@src",
                urlSelector = "@href",
                latestChapterSelector = null,
                genreSelector = null
            ),
            detailRule = com.kousoyu.drift.data.rule.DetailRule(
                titleSelector = "h1.comics-detail__title@text",
                coverSelector = "amp-img@src",
                authorSelector = "h2.comics-detail__author@text",
                descSelector = "p.comics-detail__desc@text",
                statusSelector = "div.tag-list span.tag@text",
                chapterListSelector = "div.comics-chapters a",
                chapterNameSelector = "span@text",
                chapterUrlSelector = "@href"
            ),
            chapterImagesRule = com.kousoyu.drift.data.rule.ChapterImagesRule(
                imageListSelector = "amp-img.comic-contain__item, img.comic-contain__item",
                imageUrlSelector = "@src"
            )
        )
        // Adjust the List Selector to the wrapper div so we can get both poster link and title sibling
        val advancedConfig = config.copy(
            searchRule = config.searchRule.copy(
                listSelector = "div.comics-card", 
                urlSelector = "a.comics-card__poster@href",
                titleSelector = "h3@text",
                coverSelector = "amp-img@src"
            )
        )
        
        return com.kousoyu.drift.data.rule.DynamicMangaSource(
            advancedConfig, 
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
    }

    val currentSource = MutableStateFlow<MangaSource>(sources.first())

    fun getSourceByName(name: String): MangaSource {
        return sources.find { it.name == name } ?: sources.first()
    }

    fun updateSources(remote: List<MangaSource>) {
        val newSources = mutableListOf<MangaSource>(AggregateSource())
        // For now, prepend remote dynamic sources, then append hardcoded locals that aren't dynamically supplied yet
        newSources.addAll(remote)
        
        val remoteNames = remote.map { it.name }
        
        sources.drop(1).forEach { local ->
            if (local.name !in remoteNames) {
                newSources.add(local)
            }
        }
        
        sources = newSources
        // Update current source if it was removed
        if (sources.none { it.name == currentSource.value.name }) {
            currentSource.value = sources.first()
        }
    }
}

