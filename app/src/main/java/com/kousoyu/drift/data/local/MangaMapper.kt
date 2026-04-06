package com.kousoyu.drift.data.local

import com.kousoyu.drift.data.Manga

fun MangaEntity.toDomainManga(): Manga {
    return Manga(
        title = this.title,
        coverUrl = this.coverUrl,
        detailUrl = this.detailUrl,
        latestChapter = this.lastReadChapterName ?: this.latestChapter,
        genre = this.genre,
        author = "",
        sourceName = this.sourceName
    )
}

fun Manga.toEntity(sourceName: String = this.sourceName): MangaEntity {
    return MangaEntity(
        this.detailUrl,
        this.title,
        this.coverUrl,
        sourceName,
        this.latestChapter,
        this.genre
    )
}
