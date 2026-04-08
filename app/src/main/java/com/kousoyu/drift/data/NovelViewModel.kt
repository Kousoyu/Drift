package com.kousoyu.drift.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kousoyu.drift.data.local.DriftDatabase
import com.kousoyu.drift.data.local.NovelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Novel screen.
 *
 * Key behavior:
 * - Loads data ONCE on init, caches it, doesn't reload when switching tabs
 * - Only reloads on explicit refresh() or switchSource()
 * - Per-source cache: switching back doesn't re-fetch
 * - Bookshelf: real-time Room Flow for favorites
 */
class NovelViewModel(app: Application) : AndroidViewModel(app) {

    sealed class UiState {
        data object Loading : UiState()
        data class Success(val novels: List<NovelItem>) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val dao = DriftDatabase.getDatabase(app).novelDao()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    val currentSource = NovelSourceManager.currentSource

    // Bookshelf — real-time from Room
    val bookshelf: StateFlow<List<NovelEntity>> = dao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var loadJob: Job? = null

    // Per-source cache → switching back is instant
    private val sourceCache = mutableMapOf<String, List<NovelItem>>()

    init {
        loadPopular()
    }

    fun loadPopular() {
        val sourceName = currentSource.value.name
        sourceCache[sourceName]?.let {
            _uiState.value = UiState.Success(it)
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            val source = currentSource.value
            source.getPopularNovels()
                .onSuccess {
                    sourceCache[source.name] = it
                    _uiState.value = UiState.Success(it)
                }
                .onFailure {
                    _uiState.value = UiState.Error(it.message ?: "加载失败")
                }
        }
    }

    fun search(query: String, onResult: (Result<List<NovelItem>>) -> Unit) {
        if (query.isBlank()) return
        viewModelScope.launch {
            val result = currentSource.value.searchNovel(query)
            onResult(result)
        }
    }

    fun switchSource(source: NovelSource) {
        NovelSourceManager.switchSource(source)
        loadPopular()
    }

    fun refresh() {
        sourceCache.remove(currentSource.value.name)
        loadPopular()
    }
}
