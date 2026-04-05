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
        viewModelScope.launch {
            state = DetailState.Loading
            try {
                val url = URLDecoder.decode(urlEncoded, "UTF-8")
                val explicitSourceName = if (sourceNameEncoded.isNotEmpty()) URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""
                
                // Track local DB entity (favorite status & reading progress)
                var solvedSource = if (explicitSourceName.isNotEmpty()) SourceManager.getSourceByName(explicitSourceName) else SourceManager.currentSource.value
                launch {
                    dao.getMangaByUrlFlow(url).collect { entity ->
                        _localManga.value = entity
                        // If it's a favorite, override source with the one from DB
                        if (entity != null && entity.sourceName.isNotEmpty()) {
                            solvedSource = SourceManager.getSourceByName(entity.sourceName)
                        }
                    }
                }

                val targetSource = if (explicitSourceName.isNotEmpty()) SourceManager.getSourceByName(explicitSourceName)
                    else dao.getMangaByUrlSync(url)?.sourceName?.let { SourceManager.getSourceByName(it) } ?: SourceManager.currentSource.value
                
                // Override solvedSource for favorite saving
                solvedSource = targetSource
                
                targetSource.getMangaDetail(url)
                    .onSuccess { state = DetailState.Success(it) }
                    .onFailure { state = DetailState.Error(it.message ?: "Failed to load detail") }
            } catch (e: Exception) {
                state = DetailState.Error(e.message ?: "Invalid URL")
            }
        }
    }

    fun toggleFavorite(mangaDetail: MangaDetail, currentSourceName: String = "") {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val url = mangaDetail.url
            val existing = dao.getMangaByUrlSync(url)
            if (existing != null) {
                dao.deleteMangaSync(existing)
            } else {
                val sourceName = if (currentSourceName.isNotEmpty()) currentSourceName else SourceManager.currentSource.value.name
                val entity = MangaEntity(
                    url,
                    mangaDetail.title,
                    mangaDetail.coverUrl,
                    sourceName,
                    "",
                    ""
                )
                dao.insertMangaSync(entity)
            }
        }
    }
}
