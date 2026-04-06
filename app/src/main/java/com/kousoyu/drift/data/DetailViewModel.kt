package com.kousoyu.drift.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kousoyu.drift.data.local.DriftDatabase
import com.kousoyu.drift.data.local.MangaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

sealed class DetailState {
    object Loading : DetailState()
    data class Success(val detail: MangaDetail) : DetailState()
    data class Error(val message: String) : DetailState()
}

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DriftDatabase.getDatabase(app).mangaDao()

    var state by mutableStateOf<DetailState>(DetailState.Loading)
        private set

    private val _localManga = MutableStateFlow<MangaEntity?>(null)
    val localManga: StateFlow<MangaEntity?> = _localManga

    fun loadDetail(urlEncoded: String, sourceNameEncoded: String = "") {
        val url = URLDecoder.decode(urlEncoded, "UTF-8")

        // Static cache hit → instant render, no network
        val cached = detailCache[url]
        if (cached != null) {
            state = DetailState.Success(cached)
            // Still track local DB entity in background
            viewModelScope.launch(Dispatchers.IO) {
                dao.getMangaByUrlFlow(url).collect { _localManga.value = it }
            }
            return
        }

        viewModelScope.launch {
            state = DetailState.Loading
            try {
                val explicitSourceName = if (sourceNameEncoded.isNotEmpty()) URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""

                launch(Dispatchers.IO) {
                    dao.getMangaByUrlFlow(url).collect { _localManga.value = it }
                }

                val targetSource = withContext(Dispatchers.IO) {
                    when {
                        explicitSourceName.isNotEmpty() -> SourceManager.getSourceByName(explicitSourceName)
                        else -> dao.getMangaByUrlSync(url)?.sourceName
                            ?.let { SourceManager.getSourceByName(it) }
                            ?: SourceManager.currentSource.value
                    }
                }

                targetSource.getMangaDetail(url)
                    .onSuccess { detail ->
                        // Static cache: survives ViewModel destruction
                        detailCache[url] = detail
                        while (detailCache.size > 10) detailCache.keys.first().let { detailCache.remove(it) }
                        state = DetailState.Success(detail)
                        syncChapterCount(url, detail)
                    }
                    .onFailure { state = DetailState.Error(it.message ?: "加载失败") }
            } catch (e: Exception) {
                state = DetailState.Error(e.message ?: "无效URL")
            }
        }
    }

    private fun syncChapterCount(mangaUrl: String, detail: MangaDetail) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = dao.getMangaByUrlSync(mangaUrl) ?: return@launch
            val newCount = detail.chapters.size
            if (newCount != entity.totalChapters && newCount > 0) {
                val latestName = detail.chapters.lastOrNull()?.name ?: ""
                dao.updateChapterCountSync(mangaUrl, newCount, latestName)
            }
        }
    }

    fun toggleFavorite(mangaDetail: MangaDetail, currentSourceName: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val url = mangaDetail.url
            val existing = dao.getMangaByUrlSync(url)
            if (existing != null) {
                dao.deleteMangaSync(existing)
            } else {
                val sourceName = currentSourceName.ifEmpty { SourceManager.currentSource.value.name }
                val entity = MangaEntity(url, mangaDetail.title, mangaDetail.coverUrl, sourceName, "", "")
                entity.totalChapters = mangaDetail.chapters.size
                entity.latestChapter = mangaDetail.chapters.lastOrNull()?.name ?: ""
                dao.insertMangaSync(entity)
            }
        }
    }

    companion object {
        /** Static detail cache — survives navigation. Max 10 entries. */
        private val detailCache = LinkedHashMap<String, MangaDetail>()
    }
}
