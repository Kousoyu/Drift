package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kousoyu.drift.data.NovelItem
import com.kousoyu.drift.data.NovelSourceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Novel Search Screen ─────────────────────────────────────────────────────

sealed class NovelSearchState {
    data object Idle : NovelSearchState()
    data object Loading : NovelSearchState()
    data class Success(val items: List<NovelItem>) : NovelSearchState()
    data class Error(val message: String) : NovelSearchState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelSearchScreen(
    onBack: () -> Unit,
    onNovelClick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<NovelSearchState>(NovelSearchState.Idle) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Auto-focus on entry
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun doSearch(q: String) {
        if (q.isBlank()) {
            state = NovelSearchState.Idle
            return
        }
        searchJob?.cancel()
        searchJob = coroutineScope.launch {
            state = NovelSearchState.Loading
            val source = NovelSourceManager.currentSource.value
            source.searchNovel(q)
                .onSuccess { state = NovelSearchState.Success(it) }
                .onFailure { state = NovelSearchState.Error(it.message ?: "搜索失败") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Search Bar ──
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索小说...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = { newQuery ->
                        query = newQuery
                        // Debounced search
                        searchJob?.cancel()
                        searchJob = coroutineScope.launch {
                            delay(500L)
                            doSearch(newQuery)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        doSearch(query)
                    })
                )
            }

            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                IconButton(onClick = {
                    query = ""
                    state = NovelSearchState.Idle
                    searchJob?.cancel()
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Source indicator ──
        val currentSource = NovelSourceManager.currentSource.collectAsState()
        Text(
            text = "搜索源: ${currentSource.value.name}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        // ── Results ──
        when (val s = state) {
            is NovelSearchState.Idle -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "输入关键词开始搜索",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }

            is NovelSearchState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在搜索…首次可能需要几秒",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            is NovelSearchState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("搜索失败", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.message, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }

            is NovelSearchState.Success -> {
                if (s.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("没有找到相关小说", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(s.items, key = { it.detailUrl }) { novel ->
                            SearchResultItem(novel = novel, onClick = {
                                keyboardController?.hide()
                                onNovelClick(novel.detailUrl)
                            })
                        }
                    }
                }
            }
        }
    }
}

// ─── Search Result Item ──────────────────────────────────────────────────────

@Composable
private fun SearchResultItem(novel: NovelItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Cover
            if (novel.coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(novel.coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(52.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = novel.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (novel.author.isNotEmpty()) {
                    Text(
                        text = novel.author,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (novel.status.isNotEmpty()) {
                        Text(
                            text = novel.status,
                            fontSize = 10.sp,
                            color = if ("连载" in novel.status) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    Text(
                        text = novel.sourceName,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
                }
            }
        }
    }
}
