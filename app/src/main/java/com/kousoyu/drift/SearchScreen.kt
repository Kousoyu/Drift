package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaSource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import com.kousoyu.drift.data.SourceManager

// ─── Domain & Engine ─────────────────────────────────────────────────────────

sealed class SearchResultState {
    object Idle : SearchResultState()
    object Loading : SearchResultState()
    data class Success(val items: List<Manga>) : SearchResultState()
    data class Error(val message: String) : SearchResultState()
}

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {
    // Exclude AggregateSource from search — it's a virtual fan-out, not a real source
    private val sources = SourceManager.sources.filter { it.name != "聚合 (全网)" }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _results = MutableStateFlow<Map<MangaSource, SearchResultState>>(
        sources.associateWith { SearchResultState.Idle }
    )
    val results: StateFlow<Map<MangaSource, SearchResultState>> = _results

    private var activeSearchJob: Job? = null

    init {
        // Automatic debouncer mapping query inputs to network fetches
        viewModelScope.launch {
            _searchQuery
                .debounce(600L) // Wait 600ms after user stops typing
                .collectLatest { query ->
                    if (query.isNotBlank()) {
                        executeGlobalSearch(query)
                    } else {
                        // Clear results if empty
                        _results.value = sources.associateWith { SearchResultState.Idle }
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    private suspend fun executeGlobalSearch(query: String) {
        // Cancel any pending search in-flight
        activeSearchJob?.cancel()
        
        // Reset all sources to loading state
        _results.value = sources.associateWith { SearchResultState.Loading }

        activeSearchJob = viewModelScope.launch {
            // Concurrent fan-out pattern to all Active Sources
            sources.map { source ->
                async {
                    val result = source.searchManga(query)
                    result.fold(
                        onSuccess = { items ->
                            updateSourceState(source, SearchResultState.Success(items))
                        },
                        onFailure = { err ->
                            updateSourceState(source, SearchResultState.Error(err.message ?: "Unknown Error"))
                        }
                    )
                }
            }.forEach { it.await() }
        }
    }

    private fun updateSourceState(source: MangaSource, state: SearchResultState) {
        _results.value = _results.value.toMutableMap().apply {
            put(source, state)
        }
    }
}

// ─── UI Layouts ──────────────────────────────────────────────────────────────

/**
 * Deeply minimalist and unrestrained global search screen.
 * Abiding by the philosophy of "极简，自由" (Minimalist, Free).
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    onNavigateToDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.searchQuery.collectAsState()
    val resultsMap by viewModel.results.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Automatically focus keyboard on mount
    LaunchedEffect(Unit) {
        delay(300) // Crucial: wait until navigation animation finishes completely before popping keyboard
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Massive Minimalist Header Bar
        TopSearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            focusRequester = focusRequester,
            onBack = onBack,
            onSearchSubmit = { keyboardController?.hide() }
        )

        // 2. Cross-axis Result Space
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
        ) {
            // Unpack map so we respect defined source order
            SourceManager.sources.filter { it.name != "聚合 (全网)" }.forEach { source ->
                val state = resultsMap[source] ?: SearchResultState.Idle
                
                // Only render if source is active (i.e. not idle)
                if (state !is SearchResultState.Idle) {
                    item {
                        SourceResultSection(
                            source = source,
                            state = state,
                            onNavigateToDetail = onNavigateToDetail
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onSearchSubmit: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            keyboardController?.hide()
            onBack()
        }) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索漫画...",
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                    )
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() })
            )
        }
        
        // Search Icon acts as purely aesthetic visual anchor
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (query.isEmpty()) 0.2f else 0.8f),
            modifier = Modifier.padding(end = 16.dp).size(28.dp)
        )
    }
}

@Composable
private fun SourceResultSection(
    source: MangaSource,
    state: SearchResultState,
    onNavigateToDetail: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = source.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            // Dynamic Status Indicator
            when (state) {
                is SearchResultState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
                is SearchResultState.Success -> {
                    Text(
                        text = "· ${state.items.size}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Medium
                    )
                }
                is SearchResultState.Error -> {
                    Text(
                        text = "· 异常",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
                else -> {}
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section Content
        when (state) {
            is SearchResultState.Loading -> {
                // Ghost skeleton placeholders
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(4) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
                        )
                    }
                }
            }
            is SearchResultState.Success -> {
                if (state.items.isEmpty()) {
                    Text(
                        text = "无相关内容",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(state.items, key = { it.detailUrl }) { manga ->
                            Box(modifier = Modifier.width(120.dp)) {
                                ExploreCard(
                                    manga = manga,
                                    onNavigateToDetail = onNavigateToDetail
                                )
                            }
                        }
                    }
                }
            }
            is SearchResultState.Error -> {
                Text(
                    text = state.message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            else -> {} // Idle handles by not reaching here
        }
    }
}
