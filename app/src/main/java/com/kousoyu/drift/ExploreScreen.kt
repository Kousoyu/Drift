package com.kousoyu.drift

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kousoyu.drift.data.Manga
import com.kousoyu.drift.data.MangaListState
import com.kousoyu.drift.data.MangaViewModel
import com.kousoyu.drift.ui.theme.DriftTheme

// ─── Category data ────────────────────────────────────────────────────────────

private val exploreCategories = listOf(
    "全部", "热血", "奇幻", "恋爱", "校园",
    "悬疑", "惊悚", "科幻", "都市", "古风", "治愈"
)

private enum class SortMode(val label: String) { Popular("人气"), Latest("最新") }

// ─── Explore Screen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit = {},
    onNavigateToDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: MangaViewModel = viewModel()
) {
    val popularState by viewModel.popularState.collectAsState()

    var selectedCategory by remember { mutableIntStateOf(0) }
    var sortMode by remember { mutableStateOf(SortMode.Popular) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ─── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "探索",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        // ─── Category tabs (underline style, no fill) ─────────────────────────
        CategoryUnderlineTabs(
            categories = exploreCategories,
            selected = selectedCategory,
            onSelect = { selectedCategory = it }
        )

        // ─── Sort toggle ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SortMode.entries.forEach { mode ->
                val isSelected = mode == sortMode
                Text(
                    text = mode.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { sortMode = mode }
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ─── Grid ─────────────────────────────────────────────────────────────
        when (val state = popularState) {
            is MangaListState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onBackground,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            is MangaListState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "加载失败，请返回重试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
            is MangaListState.Success -> {
                val filtered = if (selectedCategory == 0) state.items
                else state.items.filter { it.genre == exploreCategories[selectedCategory] }

                val sorted = when (sortMode) {
                    SortMode.Popular -> filtered
                    SortMode.Latest  -> filtered.sortedByDescending { it.latestChapter }
                }

                if (sorted.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "该分类暂无内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 32.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(sorted) { manga ->
                            ExploreCard(manga = manga, onNavigateToDetail = onNavigateToDetail)
                        }
                    }
                }
            }
        }
    }
}

// ─── Category underline tabs ──────────────────────────────────────────────────

@Composable
fun CategoryUnderlineTabs(
    categories: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        categories.forEachIndexed { index, label ->
            val isSelected = index == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                // The signature underline — 16dp wide, 2dp thick, animated width
                val underlineWidth by animateDpAsState(
                    targetValue = if (isSelected) 18.dp else 0.dp,
                    label = "tab_underline"
                )
                Box(
                    modifier = Modifier
                        .width(underlineWidth)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.onBackground)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ─── Explore Card (3-col, borderless) ────────────────────────────────────────

@Composable
fun ExploreCard(manga: Manga, onNavigateToDetail: (String, String) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.clickable { onNavigateToDetail(manga.detailUrl, manga.sourceName) }) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(manga.coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                // No border radius, no card shadow — ultra minimal
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = manga.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (manga.genre.isNotEmpty()) {
            Text(
                text = manga.genre,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, showSystemUi = true)
@Composable
fun ExploreScreenDarkPreview() {
    DriftTheme(darkTheme = true) {
        ExploreScreen(onBack = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, showSystemUi = true)
@Composable
fun ExploreScreenLightPreview() {
    DriftTheme(darkTheme = false) {
        ExploreScreen(onBack = {})
    }
}
