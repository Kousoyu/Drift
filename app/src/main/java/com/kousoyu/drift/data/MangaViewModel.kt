package com.kousoyu.drift.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.kousoyu.drift.data.local.DriftDatabase
import com.kousoyu.drift.data.local.MangaEntity
import com.kousoyu.drift.data.local.toDomainManga
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// ─── UI State ─────────────────────────────────────────────────────────────────

sealed class MangaListState {
    data object Loading : MangaListState()
    data class Success(val items: List<Manga>) : MangaListState()
    data class Error(val message: String) : MangaListState()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class MangaViewModel(app: Application) : AndroidViewModel(app) {

    val currentSource: StateFlow<MangaSource> = SourceManager.currentSource
    private val db = DriftDatabase.getDatabase(app)

    val favorites: StateFlow<List<MangaEntity>> = db.mangaDao().getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _popularState = MutableStateFlow<MangaListState>(MangaListState.Loading)
    val popularState: StateFlow<MangaListState> = _popularState

    private val _searchState = MutableStateFlow<MangaListState?>(null)
    val searchState: StateFlow<MangaListState?> = _searchState

    init {
        loadPopular()
    }

    fun loadPopular() {
        viewModelScope.launch {
            val src = currentSource.value
            // 1. Immediately read from cache if available (Offline-first / Instant load)
            val cacheFile = File(getApplication<Application>().cacheDir, "popular_manga_cache_${src.name}.json")
            if (cacheFile.exists()) {
                runCatching {
                    val jsonStr = cacheFile.readText()
                    val array = JSONArray(jsonStr)
                    val cachedList = mutableListOf<Manga>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        cachedList.add(
                            Manga(
                                title = obj.optString("title", ""),
                                coverUrl = obj.optString("coverUrl", ""),
                                detailUrl = obj.optString("detailUrl", ""),
                                latestChapter = obj.optString("latestChapter", ""),
                                genre = obj.optString("genre", ""),
                                author = obj.optString("author", ""),
                                sourceName = obj.optString("sourceName", src.name)
                            )
                        )
                    }
                    if (cachedList.isNotEmpty()) {
                        _popularState.value = MangaListState.Success(cachedList)
                    }
                }
            } else {
                // If no cache, explicitly show loading state
                _popularState.value = MangaListState.Loading
            }

            // 2. Fetch fresh data from network in background
            src.getPopularManga().fold(
                onSuccess = { list -> 
                    _popularState.value = MangaListState.Success(list)
                    // Write to cache
                    withContext(Dispatchers.IO) {
                        val array = JSONArray()
                        list.forEach { manga ->
                            val obj = JSONObject()
                            obj.put("title", manga.title)
                            obj.put("coverUrl", manga.coverUrl)
                            obj.put("detailUrl", manga.detailUrl)
                            obj.put("latestChapter", manga.latestChapter)
                            obj.put("genre", manga.genre)
                            obj.put("author", manga.author)
                            obj.put("sourceName", manga.sourceName)
                            array.put(obj)
                        }
                        cacheFile.writeText(array.toString())
                    }
                },
                onFailure = { 
                    // Only show error screen if we don't even have cached data
                    if (_popularState.value !is MangaListState.Success) {
                        _popularState.value = MangaListState.Error(it.message ?: "未知错误") 
                    }
                }
            )
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _searchState.value = null
            return
        }
        _searchState.value = MangaListState.Loading
        viewModelScope.launch {
            SourceManager.currentSource.value.searchManga(query).fold(
                onSuccess = { _searchState.value = MangaListState.Success(it) },
                onFailure = { _searchState.value = MangaListState.Error(it.message ?: "搜索失败") }
            )
        }
    }

    fun clearSearch() {
        _searchState.value = null
    }

    fun switchSource(mangaSource: MangaSource) {
        if (SourceManager.currentSource.value.name == mangaSource.name) return
        SourceManager.currentSource.value = mangaSource
        _searchState.value = null
        _popularState.value = MangaListState.Loading
        loadPopular()
    }
}
