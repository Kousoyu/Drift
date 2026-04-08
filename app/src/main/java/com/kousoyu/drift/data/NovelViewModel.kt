package com.kousoyu.drift.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 */
class NovelViewModel : ViewModel() {

    sealed class UiState {
        data object Loading : UiState()
        data class Success(val novels: List<NovelItem>) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    private val _searchResults = MutableStateFlow<UiState>(UiState.Success(emptyList()))
    val searchResults: StateFlow<UiState> = _searchResults

    val currentSource = NovelSourceManager.currentSource

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    // Per-source cache → switching back is instant
    private val sourceCache = mutableMapOf<String, List<NovelItem>>()

    init {
        // Load once on creation — ViewModel survives tab switches
        loadPopular()
    }

    /**
     * Load popular novels. Uses cache if available.
     */
    fun loadPopular() {
        val sourceName = currentSource.value.name

        // Cache hit → instant display
        sourceCache[sourceName]?.let {
            _uiState.value = UiState.Success(it)
            return
        }

        // Cache miss → fetch
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

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchResults.value = UiState.Loading
            currentSource.value.searchNovel(query)
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "搜索失败") }
        }
    }

    fun switchSource(source: NovelSource) {
        NovelSourceManager.switchSource(source)
        loadPopular()  // Will use cache if available
    }

    /**
     * Force refresh — clears cache for current source.
     */
    fun refresh() {
        sourceCache.remove(currentSource.value.name)
        loadPopular()
    }
}
