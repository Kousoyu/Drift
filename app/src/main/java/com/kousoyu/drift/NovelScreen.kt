package com.kousoyu.drift

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kousoyu.drift.data.NovelItem
import com.kousoyu.drift.data.NovelSourceManager
import com.kousoyu.drift.data.NovelViewModel
import com.kousoyu.drift.data.local.NovelEntity
import kotlinx.coroutines.launch
import java.util.Calendar

// ─── Novel Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelScreen(
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToBookshelf: () -> Unit = {},
    novelViewModel: NovelViewModel = viewModel()
) {
    val uiState by novelViewModel.uiState.collectAsState()
    val currentSource by novelViewModel.currentSource.collectAsState()
    val bookshelf by novelViewModel.bookshelf.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    var isPullRefreshing by remember { mutableStateOf(false) }

    // Reset refreshing when data arrives
    LaunchedEffect(uiState) {
        if (uiState !is NovelViewModel.UiState.Loading) isPullRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                novelViewModel.refresh()
            },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header: greeting + search bar + bookshelf icon ──
            item {
                NovelHeader(
                    bookshelfCount = bookshelf.size,
                    onSearchClick = onNavigateToSearch,
                    onBookshelfClick = onNavigateToBookshelf
                )
            }

            // ── Source Switcher ──
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val allSources = NovelSourceManager.sources
                    allSources.forEach { src ->
                        val isSelected = src.name == currentSource.name
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            onClick = { if (!isSelected) novelViewModel.switchSource(src) }
                        ) {
                            Text(
                                text = src.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = { novelViewModel.refresh() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Bookshelf peek (if not empty) ──
            if (bookshelf.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("继续阅读", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.weight(1f))
                        if (bookshelf.size > 3) {
                            Text(
                                text = "全部 ${bookshelf.size} →",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { onNavigateToBookshelf() }
                            )
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    BookshelfPeekRow(
                        novels = bookshelf.take(6),
                        onNovelClick = onNavigateToDetail
                    )
                }
            }

            // ── Content State ──
            when (val state = uiState) {
                is NovelViewModel.UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "加载中…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                is NovelViewModel.UiState.Error -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("加载失败", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text(state.message, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 40.dp))
                                Spacer(Modifier.height(16.dp))
                                OutlinedButton(onClick = { novelViewModel.refresh() }) { Text("重试") }
                            }
                        }
                    }
                }

                is NovelViewModel.UiState.Success -> {
                    val novels = state.novels
                    if (novels.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无数据", fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    } else {
                        // ── Featured (first item, no fake "编辑推荐") ──
                        val featured = novels.first()
                        item {
                            Spacer(modifier = Modifier.height(20.dp))
                            NovelSectionHeader(title = "热门推荐")
                        }
                        item {
                            Spacer(modifier = Modifier.height(10.dp))
                            FeaturedNovelCard(novel = featured, onClick = {
                                onNavigateToDetail(featured.detailUrl)
                            })
                        }

                        // ── Trending (horizontal scroll, items 2-9) ──
                        if (novels.size > 1) {
                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                                NovelSectionHeader(title = "排行榜")
                            }
                            item {
                                Spacer(modifier = Modifier.height(10.dp))
                                RankingRow(novels = novels.drop(1).take(8), onNovelClick = onNavigateToDetail)
                            }
                        }

                        // ── Full list ──
                        if (novels.size > 3) {
                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                                NovelSectionHeader(title = "更多")
                            }
                            item { Spacer(modifier = Modifier.height(4.dp)) }
                            items(novels.drop(3), key = { it.detailUrl }) { novel ->
                                NovelListItem(novel, onClick = { onNavigateToDetail(novel.detailUrl) })
                            }
                        }
                    }
                }
            }

            // ── Footer ──
            item {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "数据来源: ${currentSource.name}\n仅供学习交流使用",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    fontSize = 11.sp, lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        } // PullToRefreshBox

        // ── Scroll to Top ──
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Surface(
                onClick = {
                    coroutineScope.launch {
                        if (listState.firstVisibleItemIndex > 6) listState.scrollToItem(0)
                        else listState.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.size(44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "回到顶部",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("TOP", color = MaterialTheme.colorScheme.background, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Header: Greeting + Search Bar + Bookshelf Icon ──────────────────────────

@Composable
private fun NovelHeader(
    bookshelfCount: Int,
    onSearchClick: () -> Unit,
    onBookshelfClick: () -> Unit
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 6  -> "静夜好读"
        hour < 12 -> "晨读时光"
        hour < 18 -> "午后书卷"
        else      -> "枕边故事"
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tightTop = androidx.compose.ui.unit.max(0.dp, topInset - 26.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tightTop)
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = greeting,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = "小说",
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Search bar + Bookshelf icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fake search bar (tappable → navigates to search screen)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                onClick = onSearchClick
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = "搜索小说、作者...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }

            // Bookshelf icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (bookshelfCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                onClick = onBookshelfClick
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.CollectionsBookmark,
                        contentDescription = "书架",
                        modifier = Modifier.size(20.dp),
                        tint = if (bookshelfCount > 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    // Badge
                    if (bookshelfCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (bookshelfCount > 9) "9+" else "$bookshelfCount",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Bookshelf Peek Row ──────────────────────────────────────────────────────

@Composable
private fun BookshelfPeekRow(novels: List<NovelEntity>, onNovelClick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(novels, key = { it.detailUrl }) { novel ->
            Surface(
                onClick = { onNovelClick(novel.detailUrl) },
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent
            ) {
                Column(
                    modifier = Modifier.width(80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        if (novel.coverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(novel.coverUrl).crossfade(true).build(),
                                contentDescription = novel.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📖", fontSize = 20.sp)
                            }
                        }

                        // Reading progress overlay
                        if (novel.lastReadChapterName != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                                        )
                                    )
                                    .padding(horizontal = 4.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = novel.lastReadChapterName ?: "",
                                    fontSize = 8.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Text(
                        text = novel.title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ─── Section Header ──────────────────────────────────────────────────────────

@Composable
private fun NovelSectionHeader(title: String) {
    Text(
        text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

// ─── Featured Novel Card (honest, no fake badge) ─────────────────────────────

@Composable
private fun FeaturedNovelCard(novel: NovelItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NovelCover(
                url = novel.coverUrl,
                modifier = Modifier.width(90.dp).height(128.dp).clip(RoundedCornerShape(10.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Rank #1 badge (honest)
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFFFF6B35).copy(alpha = 0.15f)
                ) {
                    Text("🔥 No.1", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B35),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(novel.title, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (novel.author.isNotEmpty()) {
                    Text(novel.author, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (novel.genre.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)) {
                            Text(novel.genre, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (novel.status.isNotEmpty()) {
                        Text(novel.status, fontSize = 10.sp,
                            color = if ("连载" in novel.status) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ─── Ranking Row ─────────────────────────────────────────────────────────────

@Composable
private fun RankingRow(novels: List<NovelItem>, onNovelClick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(novels) { index, novel ->
            RankingCard(rank = index + 2, novel = novel, onClick = { onNovelClick(novel.detailUrl) })
        }
    }
}

@Composable
private fun RankingCard(rank: Int, novel: NovelItem, onClick: () -> Unit) {
    val rankColor = when (rank) {
        2 -> Color(0xFFFF6B35)
        3 -> Color(0xFFFF8F5E)
        4 -> Color(0xFFFFB088)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Surface(
        modifier = Modifier.width(120.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        onClick = onClick
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                NovelCover(url = novel.coverUrl, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier.padding(6.dp).size(22.dp)
                        .clip(RoundedCornerShape(6.dp)).background(rankColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$rank", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(novel.title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                if (novel.author.isNotEmpty()) {
                    Text(novel.author, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─── Novel List Item ─────────────────────────────────────────────────────────

@Composable
private fun NovelListItem(novel: NovelItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp), color = Color.Transparent, onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NovelCover(
                url = novel.coverUrl,
                modifier = Modifier.width(56.dp).height(78.dp).clip(RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(novel.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (novel.author.isNotEmpty()) {
                    Text(novel.author, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (novel.genre.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)) {
                            Text(novel.genre, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                    if (novel.status.isNotEmpty()) {
                        Text(novel.status, fontSize = 10.sp,
                            color = if ("连载" in novel.status) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    }
                    Text(novel.sourceName, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

// ─── Novel Cover ─────────────────────────────────────────────────────────────

@Composable
private fun NovelCover(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (url.isNotEmpty()) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
            contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("📖", fontSize = 24.sp)
        }
    }
}
