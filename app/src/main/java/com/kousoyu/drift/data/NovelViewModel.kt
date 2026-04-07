package com.kousoyu.drift.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Novel screen.
 * Handles loading popular novels, search, and source switching.
 *
 * Race condition protection: cancels previous load when source switches.
 */
class NovelViewModel : ViewModel() {

    // ─── State ──────────────────────────────────────────────────────────────

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

    // ─── Actions ────────────────────────────────────────────────────────────

    /**
     * Load popular novels from current source.
     */
    fun loadPopular() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            val source = currentSource.value
            source.getPopularNovels()
                .onSuccess { _uiState.value = UiState.Success(it) }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "加载失败") }
        }
    }

    /**
     * Search novels from current source.
     */
    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchResults.value = UiState.Loading
            val source = currentSource.value
            source.searchNovel(query)
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "搜索失败") }
        }
    }

    /**
     * Switch to a different novel source.
     */
    fun switchSource(source: NovelSource) {
        NovelSourceManager.switchSource(source)
        loadPopular()
    }

    /**
     * Refresh data from current source.
     */
    fun refresh() {
        loadPopular()
    }
}
