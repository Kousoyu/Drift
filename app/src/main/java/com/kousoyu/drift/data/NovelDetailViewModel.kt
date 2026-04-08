package com.kousoyu.drift.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kousoyu.drift.data.local.DriftDatabase
import com.kousoyu.drift.data.local.NovelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages novel bookshelf state + favorite toggle.
 * Parallel to DetailViewModel for manga.
 */
class NovelDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DriftDatabase.getDatabase(app).novelDao()

    private val _localNovel = MutableStateFlow<NovelEntity?>(null)
    val localNovel: StateFlow<NovelEntity?> = _localNovel

    /**
     * Start observing the bookshelf state for a given novel URL.
     */
    fun observe(novelUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.isFavorite(novelUrl).collect { isFav ->
                _localNovel.value = if (isFav) dao.getNovelByUrlSync(novelUrl) else null
            }
        }
    }

    /**
     * Toggle favorite: add if not exists, remove if exists.
     */
    fun toggleFavorite(detail: NovelDetail) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getNovelByUrlSync(detail.url)
            if (existing != null) {
                dao.deleteNovelSync(existing)
            } else {
                val sourceName = NovelSourceManager.currentSource.value.name
                val totalChapters = detail.volumes.sumOf { it.chapters.size }
                val entity = NovelEntity(detail.url, detail.title, detail.coverUrl, sourceName, detail.author)
                entity.totalChapters = totalChapters
                dao.insertNovelSync(entity)
            }
        }
    }

    /**
     * Save reading progress to Room (called from reader).
     */
    fun saveProgress(novelUrl: String, chapterName: String, chapterUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReadingProgressSync(novelUrl, chapterName, chapterUrl)
        }
    }
}
