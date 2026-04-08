package com.kousoyu.drift

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

@Composable
fun NovelScreen(
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    novelViewModel: NovelViewModel = viewModel()
) {
    val uiState by novelViewModel.uiState.collectAsState()
    val currentSource by novelViewModel.currentSource.collectAsState()
    val bookshelf by novelViewModel.bookshelf.collectAsState()

    // 0 = 书架, 1 = 发现
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = selectedTab, label = "novel_tab") { tab ->
            when (tab) {
                0 -> NovelBookshelfContent(
                    bookshelf = bookshelf,
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it },
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onSwitchToDiscover = { selectedTab = 1 }
                )
                1 -> NovelDiscoverContent(
                    uiState = uiState,
                    currentSource = currentSource,
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it },
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onRefresh = { novelViewModel.refresh() },
                    onSwitchSource = { novelViewModel.switchSource(it) },
                    listState = listState
                )
            }
        }

        // ── Scroll to Top (discover mode only) ──
        if (selectedTab == 1) {
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
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun NovelHeader(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onSearchClick: () -> Unit
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
        // Greeting + Search
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
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
            }

            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tabs: 书架 / 发现
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("书架", "发现").forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                    onClick = { onTabChange(index) }
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ─── Bookshelf Content ───────────────────────────────────────────────────────

@Composable
private fun NovelBookshelfContent(
    bookshelf: List<NovelEntity>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onSwitchToDiscover: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NovelHeader(
            selectedTab = selectedTab,
            onTabChange = onTabChange,
            onSearchClick = onNavigateToSearch
        )

        if (bookshelf.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("📚", fontSize = 48.sp)
                    Text(
                        text = "还没有收藏小说",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    OutlinedButton(
                        onClick = onSwitchToDiscover,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("去发现", fontSize = 13.sp)
                    }
                }
            }
        } else {
            // Bookshelf grid
            Spacer(modifier = Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookshelf, key = { it.detailUrl }) { novel ->
                    BookshelfItem(novel = novel, onClick = {
                        onNavigateToDetail(novel.detailUrl)
                    })
                }
            }
        }
    }
}

@Composable
private fun BookshelfItem(novel: NovelEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                if (novel.coverUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(novel.coverUrl)
                            .crossfade(true)
                            .build(),
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
                        Text("📖", fontSize = 24.sp)
                    }
                }

                // Reading progress badge
                if (novel.lastReadChapterName != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                                )
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = novel.lastReadChapterName ?: "",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Title
            Text(
                text = novel.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

// ─── Discover Content ────────────────────────────────────────────────────────

@Composable
private fun NovelDiscoverContent(
    uiState: NovelViewModel.UiState,
    currentSource: com.kousoyu.drift.data.NovelSource,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchSource: (com.kousoyu.drift.data.NovelSource) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // ── Header ──
        item {
            NovelHeader(
                selectedTab = selectedTab,
                onTabChange = onTabChange,
                onSearchClick = onNavigateToSearch
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
                        onClick = { if (!isSelected) onSwitchSource(src) }
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
                    onClick = onRefresh,
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
                            OutlinedButton(onClick = onRefresh) { Text("重试") }
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
                    // Featured
                    val featured = novels.first()
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        FeaturedNovelCard(novel = featured, onClick = { onNavigateToDetail(featured.detailUrl) })
                    }

                    // Trending
                    if (novels.size > 1) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            NovelSectionHeader(title = "热门排行")
                        }
                        item {
                            Spacer(modifier = Modifier.height(10.dp))
                            RankingRow(novels = novels.drop(1).take(8), onNovelClick = onNavigateToDetail)
                        }
                    }

                    // Full list
                    if (novels.size > 3) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            NovelSectionHeader(title = "更多推荐")
                        }
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                        items(novels.drop(3), key = { it.detailUrl }) { novel ->
                            NovelListItem(novel, onClick = { onNavigateToDetail(novel.detailUrl) })
                        }
                    }
                }
            }
        }

        // Footer
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

// ─── Featured Novel Card ─────────────────────────────────────────────────────

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
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text("编辑推荐", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
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
