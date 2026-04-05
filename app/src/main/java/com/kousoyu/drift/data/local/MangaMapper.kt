package com.kousoyu.drift.data.local

import com.kousoyu.drift.data.Manga

// Extension function to map Entity back to UI Domain Model
fun MangaEntity.toDomainManga(): Manga {
    return Manga(
        title = this.title,
        coverUrl = this.coverUrl,
        detailUrl = this.detailUrl,
        latestChapter = this.lastReadChapterName ?: this.latestChapter,
        genre = this.genre,
        author = "", // author is not mapped in entity directly
        sourceName = this.sourceName
    )
}

// Extension function to map Domain Model to Entity
fun Manga.toEntity(sourceName: String = "包子漫画"): MangaEntity {
    return MangaEntity(
        this.detailUrl,
        this.title,
        this.coverUrl,
        sourceName,
        this.latestChapter,
        this.genre
    )
}
