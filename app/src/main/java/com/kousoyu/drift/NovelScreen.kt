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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Calendar

// ─── Data Model ──────────────────────────────────────────────────────────────

data class Novel(
    val title: String,
    val author: String,
    val genre: String = "",
    val wordCount: String = "",
    val status: String = "",      // 连载中 / 已完结
    val description: String = "",
    val quote: String = "",       // 精选句子
    val coverUrl: String = ""
)

// ─── Demo Data ───────────────────────────────────────────────────────────────

private val demoNovels = listOf(
    Novel(
        title = "诡秘之主",
        author = "爱潜水的乌贼",
        genre = "玄幻",
        wordCount = "469万字",
        status = "已完结",
        description = "蒸汽与机械的浪潮中，谁能触及非凡？",
        quote = "\"每个人都是自己人生的主角，但不是每个人都能控制剧本。\""
    ),
    Novel(
        title = "凡人修仙传",
        author = "忘语",
        genre = "仙侠",
        wordCount = "746万字",
        status = "已完结",
        description = "一个普通少年的修仙之路，从山村到星海。"
    ),
    Novel(
        title = "大奉打更人",
        author = "卖报小郎君",
        genre = "古典仙侠",
        wordCount = "423万字",
        status = "已完结",
        description = "身穿大奉王朝，从一介打更人到庙堂之高。"
    ),
    Novel(
        title = "家族修仙：我打造万年世家",
        author = "万年天帝",
        genre = "玄幻",
        wordCount = "280万字",
        status = "连载中",
        description = "穿越修仙世界，一代代经营，铸就万古家族。"
    ),
    Novel(
        title = "深空彼岸",
        author = "辰东",
        genre = "科幻",
        wordCount = "350万字",
        status = "连载中",
        description = "星空之下，人类文明向宇宙深处进发。"
    ),
    Novel(
        title = "道诡异仙",
        author = "狐尾的笔",
        genre = "悬疑",
        wordCount = "310万字",
        status = "连载中",
        description = "当修仙遇到诡异世界，细思极恐的东方异闻录。"
    )
)

private val genreFilters = listOf("全部", "玄幻", "仙侠", "科幻", "悬疑", "都市", "历史")

// ─── Novel Screen ────────────────────────────────────────────────────────────

@Composable
fun NovelScreen(
    onNavigateToSearch: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    var selectedGenre by remember { mutableStateOf("全部") }

    val filteredNovels = if (selectedGenre == "全部") demoNovels
                         else demoNovels.filter { it.genre == selectedGenre }

    val featured = demoNovels.first()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header ──
            item { NovelHeader(onSearchClick = onNavigateToSearch) }

            // ── Featured Card ──
            item {
                Spacer(modifier = Modifier.height(16.dp))
                FeaturedNovelCard(novel = featured)
            }

            // ── Genre Filter Chips ──
            item {
                Spacer(modifier = Modifier.height(28.dp))
                GenreFilterRow(
                    genres = genreFilters,
                    selected = selectedGenre,
                    onSelect = { selectedGenre = it }
                )
            }

            // ── Novel List ──
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(filteredNovels, key = { it.title }) { novel ->
                NovelListItem(novel = novel)
            }

            // ── Footer ──
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "更多小说源正在接入中\n当前为演示数据",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

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
                    modifier = Modifier.size(52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "回到顶部",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "TOP",
                        color = MaterialTheme.colorScheme.background,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun NovelHeader(onSearchClick: () -> Unit) {
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
            .padding(bottom = 6.dp)
    ) {
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
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search bar
        Surface(
            onClick = onSearchClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "搜索小说、作者...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 15.sp
                )
            }
        }
    }
}

// ─── Featured Novel Card ─────────────────────────────────────────────────────

@Composable
private fun FeaturedNovelCard(novel: Novel) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(24.dp)
        ) {
            Column {
                // Genre + Status tag
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "编辑推荐",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                    ) {
                        Text(
                            text = novel.genre,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = novel.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = (-0.3).sp,
                    lineHeight = 30.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Author
                Text(
                    text = novel.author,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                // Quote
                if (novel.quote.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = novel.quote,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic,
                        lineHeight = 20.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Meta row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = novel.wordCount,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Text(
                        text = novel.status,
                        fontSize = 12.sp,
                        color = if (novel.status == "连载中") MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ─── Genre Filter ────────────────────────────────────────────────────────────

@Composable
private fun GenreFilterRow(
    genres: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(genres) { genre ->
            val isSelected = genre == selected
            Surface(
                onClick = { onSelect(genre) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = genre,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.background
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}

// ─── Novel List Item ─────────────────────────────────────────────────────────

@Composable
private fun NovelListItem(novel: Novel) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = novel.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = novel.author,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                if (novel.description.isNotEmpty()) {
                    Text(
                        text = novel.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                    ) {
                        Text(
                            text = novel.genre,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = novel.wordCount,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = novel.status,
                        fontSize = 10.sp,
                        color = if (novel.status == "连载中") MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
