package com.kousoyu.drift.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.kousoyu.drift.data.local.DriftDatabase
import com.kousoyu.drift.data.local.MangaEntity
import com.kousoyu.drift.data.local.toDomainManga
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
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

    private var loadJob: Job? = null

    init {
        loadPopular()
    }

    fun loadPopular() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val src = currentSource.value
            val cacheFile = File(getApplication<Application>().cacheDir, "popular_manga_cache_${src.name}.json")

            // 1. Instantly render from cache (zero wait)
            if (cacheFile.exists()) {
                runCatching {
                    val cachedList = parseCacheFile(cacheFile, src.name)
                    if (cachedList.isNotEmpty()) _popularState.value = MangaListState.Success(cachedList)
                }
            } else {
                _popularState.value = MangaListState.Loading
            }

            // 2. Fetch via streaming flow: update UI as each source responds
            val aggregate = src as? AggregateSource
            if (aggregate != null) {
                aggregate.getPopularMangaFlow()
                    .catch { /* silent — we already have cache */ }
                    .collect { list ->
                        if (list.isNotEmpty()) {
                            _popularState.value = MangaListState.Success(list)
                            writeCacheFile(cacheFile, list)
                        }
                    }
            } else {
                // Non-aggregate source: single blocking call
                src.getPopularManga().fold(
                    onSuccess = { list ->
                        _popularState.value = MangaListState.Success(list)
                        writeCacheFile(cacheFile, list)
                    },
                    onFailure = {
                        if (_popularState.value !is MangaListState.Success) {
                            _popularState.value = MangaListState.Error(it.message ?: "未知错误")
                        }
                    }
                )
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { _searchState.value = null; return }
        _searchState.value = MangaListState.Loading
        viewModelScope.launch {
            val src = SourceManager.currentSource.value
            val aggregate = src as? AggregateSource
            if (aggregate != null) {
                aggregate.searchMangaFlow(query)
                    .catch { _searchState.value = MangaListState.Error(it.message ?: "搜索失败") }
                    .collect { list ->
                        _searchState.value = if (list.isEmpty()) MangaListState.Loading
                                             else MangaListState.Success(list)
                    }
            } else {
                src.searchManga(query).fold(
                    onSuccess = { _searchState.value = MangaListState.Success(it) },
                    onFailure = { _searchState.value = MangaListState.Error(it.message ?: "搜索失败") }
                )
            }
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

    // ─── Cache helpers ────────────────────────────────────────────────────────

    private fun parseCacheFile(file: java.io.File, sourceName: String): List<Manga> {
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Manga(
                title          = o.optString("title"),
                coverUrl       = o.optString("coverUrl"),
                detailUrl      = o.optString("detailUrl"),
                latestChapter  = o.optString("latestChapter"),
                genre          = o.optString("genre"),
                author         = o.optString("author"),
                sourceName     = o.optString("sourceName", sourceName)
            )
        }
    }

    private suspend fun writeCacheFile(file: java.io.File, list: List<Manga>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val array = JSONArray()
                list.forEach { m ->
                    array.put(JSONObject().apply {
                        put("title",         m.title)
                        put("coverUrl",      m.coverUrl)
                        put("detailUrl",     m.detailUrl)
                        put("latestChapter", m.latestChapter)
                        put("genre",         m.genre)
                        put("author",        m.author)
                        put("sourceName",    m.sourceName)
                    })
                }
                file.writeText(array.toString())
            }
        }
    }
}
