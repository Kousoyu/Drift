package com.kousoyu.drift

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.kousoyu.drift.data.SourceManager
import com.kousoyu.drift.data.local.toDomainManga
import com.kousoyu.drift.ui.theme.DriftTheme
import com.kousoyu.drift.ui.theme.shimmerEffect
import kotlinx.coroutines.launch
import java.util.Calendar

// ─── Shimmer / Skeleton Composables ──────────────────────────────────────────

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.shimmerEffect())
}

@Composable
fun MangaScreenSkeleton() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Skeleton: Header Row (greeting + source badge)
        item {
            val topPadding = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val tightPadding = androidx.compose.ui.unit.max(0.dp, topPadding - 26.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tightPadding)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBox(
                        modifier = Modifier
                            .width(80.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .width(140.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
                ShimmerBox(
                    modifier = Modifier
                        .width(80.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                )
            }
        }
        // Skeleton: Search bar
        item {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
        }
        // Skeleton: Hero banner
        item {
            Spacer(modifier = Modifier.height(20.dp))
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        }
        // Skeleton: Section header
        item {
            ShimmerBox(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .width(80.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        // Skeleton: Horizontal list — EXACT same dimensions as RealHorizontalList
        item {
            Row(
                modifier = Modifier.padding(start = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                repeat(5) {
                    Column(modifier = Modifier.width(100.dp)) {
                        ShimmerBox(
                            modifier = Modifier
                                .width(100.dp)
                                .height(133.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaScreen(
    onNavigateToNotice: () -> Unit = {},
    onNavigateToExplore: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: MangaViewModel = viewModel()
) {
    val popularState by viewModel.popularState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()

    var showSourceSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    suspend fun smartScrollToTop() {
        if (listState.firstVisibleItemIndex > 6) listState.scrollToItem(0)
        else listState.animateScrollToItem(0)
    }

    // Cached images — keep last successful list visible during refresh
    val lastSuccessItems = remember { mutableStateOf<List<Manga>>(emptyList()) }
    val isRefreshing = popularState is MangaListState.Loading && lastSuccessItems.value.isNotEmpty()

    LaunchedEffect(popularState) {
        if (popularState is MangaListState.Success) {
            lastSuccessItems.value = (popularState as MangaListState.Success).items
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Core content rendering ──
        when {
            // First load: no cached data, show skeleton
            popularState is MangaListState.Loading && lastSuccessItems.value.isEmpty() -> {
                MangaScreenSkeleton()
            }

            // Error: no prior data — show error
            popularState is MangaListState.Error && lastSuccessItems.value.isEmpty() -> {
                ErrorScreen(
                    message = (popularState as MangaListState.Error).message,
                    onRetry = { viewModel.loadPopular() }
                )
            }

            // Success or refreshing (with data) — show full UI
            else -> {
                val displayItems = if (popularState is MangaListState.Success) {
                    (popularState as MangaListState.Success).items
                } else {
                    lastSuccessItems.value
                }

                val heroBanners = listOf(
                    Manga(
                        title = "反转练习生",
                        coverUrl = "android.resource://com.kousoyu.drift/" + R.drawable.hero_fz,
                        detailUrl = "fixed_fanzhuan",
                        latestChapter = "👑 站长强推神作",
                        genre = "独家定制",
                        sourceName = "Drift"
                    ),
                    Manga(
                        title = "三流反派的学院生存记",
                        coverUrl = "android.resource://com.kousoyu.drift/" + R.drawable.hero_2,
                        detailUrl = "fixed_2",
                        latestChapter = "👑 站长强推神作",
                        genre = "独家定制",
                        sourceName = "Drift"
                    ),
                    Manga(
                        title = "无限魔法师",
                        coverUrl = "android.resource://com.kousoyu.drift/" + R.drawable.hero_3,
                        detailUrl = "fixed_3",
                        latestChapter = "👑 站长强推神作",
                        genre = "独家定制",
                        sourceName = "聚合 (全网)"
                    ),
                    Manga(
                        title = "铁血剑家猎犬的回归",
                        coverUrl = "android.resource://com.kousoyu.drift/" + R.drawable.hero_4,
                        detailUrl = "fixed_4",
                        latestChapter = "👑 站长强推神作",
                        genre = "独家定制",
                        sourceName = "聚合 (全网)"
                    ),
                    Manga(
                        title = "三流恶棍的退休生活",
                        coverUrl = "android.resource://com.kousoyu.drift/" + R.drawable.hero_5,
                        detailUrl = "fixed_5",
                        latestChapter = "👑 站长强推神作",
                        genre = "独家定制",
                        sourceName = "聚合 (全网)"
                    )
                )

                val recentManga = displayItems.take(8)

                // Fade items in when transitioning from skeleton → content
                val contentAlpha by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 350),
                    label = "content_fade"
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .graphicsLayer { alpha = contentAlpha },
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item {
                        // ── NEW: Two-layer header ──
                        TopHeader(
                            sourceName = currentSource.name,
                            onSourceClick = { showSourceSheet = true },
                            onSearchClick = onNavigateToSearch,
                            onExploreClick = onNavigateToExplore
                        )
                    }

                    if (heroBanners.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            RealHeroBannerPager(
                                items = heroBanners,
                                onNavigateToDetail = { url, src -> onNavigateToDetail(url, src) }
                            )
                        }
                    }

                    item { SectionHeader(title = "最近在追", actionLabel = "更多 →") }
                    item {
                        RealHorizontalList(
                            items = recentManga,
                            onNavigateToDetail = { url, src -> onNavigateToDetail(url, src) }
                        )
                    }

                    item { SectionHeader(title = "书架", actionLabel = null) }
                    if (favorites.isEmpty()) {
                        item { BookshelfSection(onExploreClick = onNavigateToExplore) }
                    } else {
                        item {
                            RealHorizontalList(
                                items = favorites.map { it.toDomainManga() },
                                onNavigateToDetail = { url, src -> onNavigateToDetail(url, src) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        FooterNoticeRow(onNavigateToNotice = onNavigateToNotice)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }

        // ── Slim top loading indicator when refreshing with existing data ──
        val loaderTopInset = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val tightLoaderTop = androidx.compose.ui.unit.max(0.dp, loaderTopInset - 26.dp)

        AnimatedVisibility(
            visible = isRefreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = tightLoaderTop),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }

        // ── Smart Scroll-to-Top FAB ──
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Surface(
                onClick = { coroutineScope.launch { smartScrollToTop() } },
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

    if (showSourceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "选择图源",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "切换后内容将从相应图源加载",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                SourceManager.sources.forEach { src ->
                    val isSelected = currentSource.name == src.name
                    Surface(
                        onClick = {
                            viewModel.switchSource(src)
                            showSourceSheet = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = src.name,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                )
                                if (src.baseUrl.isNotEmpty()) {
                                    Text(
                                        text = src.baseUrl,
                                        fontSize = 11.sp,
                                        color = (if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            if (isSelected) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Box(modifier = Modifier.size(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Two-layer Top Header ────────────────────────────────────────────────────

@Composable
fun TopHeader(
    sourceName: String,
    onSourceClick: () -> Unit,
    onSearchClick: () -> Unit,
    onExploreClick: () -> Unit
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when {
        hour < 6  -> "夜深了，别忘了休息"
        hour < 12 -> "早上好，来看看今日更新"
        hour < 18 -> "下午好，刷新内容中"
        else      -> "晚上好，今天追了多少章？"
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
        // Layer 1: Greeting + Source selector
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
                    text = "Drift",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = (-0.5).sp
                )
            }

            // Source badge
            Surface(
                onClick = onSourceClick,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = sourceName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "切换图源",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Layer 2: Full-width search bar + explore
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Full-width search pill
            Surface(
                onClick = onSearchClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shadowElevation = 0.dp
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
                        text = "搜索漫画、作者...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                }
            }

            // Explore button — compact square
            Surface(
                onClick = onExploreClick,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "探索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ─── Error Screen ─────────────────────────────────────────────────────────────

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "加载失败",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.onBackground
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "重试",
                        color = MaterialTheme.colorScheme.background,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─── Bookshelf Section (empty state) ─────────────────────────────────────────

@Composable
fun BookshelfSection(onExploreClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "书架还是空的",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                onClick = onExploreClick,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "去探索 →",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}

// ─── Real Hero Banner ─────────────────────────────────────────────────────────

@Composable
fun RealHeroBannerPager(
    items: List<Manga>,
    onNavigateToDetail: (String, String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val manga = items[page]
            Box(modifier = Modifier.fillMaxSize().clickable { onNavigateToDetail(manga.detailUrl, manga.sourceName) }) {
                // Background blur
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(manga.coverUrl)
                        .crossfade(300)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(24.dp)
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))

                // Foreground cover image
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(manga.coverUrl)
                        .crossfade(300)
                        .build(),
                    contentDescription = manga.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // Bottom gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.65f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xEE000000))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp, bottom = 20.dp)
                ) {
                    if (manga.genre.isNotEmpty()) {
                        Text(
                            text = manga.genre,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = manga.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (manga.latestChapter.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = manga.latestChapter,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "${page + 1}/${items.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Dot indicators
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size) { index ->
                val isSelected = pagerState.currentPage == index
                val width by animateDpAsState(
                    targetValue = if (isSelected) 20.dp else 5.dp,
                    label = "dot_width"
                )
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Color.White else Color.White.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }
}

// ─── Real Horizontal List ─────────────────────────────────────────────────────

@Composable
fun RealHorizontalList(
    items: List<Manga>,
    onNavigateToDetail: (String, String) -> Unit
) {
    val context = LocalContext.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(items, key = { "${it.sourceName}_${it.detailUrl}" }) { manga ->
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .clickable { onNavigateToDetail(manga.detailUrl, manga.sourceName) }
            ) {
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(133.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(manga.coverUrl)
                            .crossfade(300)
                            .build(),
                        contentDescription = manga.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Source badge in corner for aggregated results
                    if (manga.sourceName.isNotEmpty() && manga.sourceName != "Drift") {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = manga.sourceName.take(2),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = manga.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = manga.latestChapter.ifEmpty { manga.sourceName },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─── Real Manga Card (Grid) ───────────────────────────────────────────────────

@Composable
fun RealMangaCard(manga: Manga, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(manga.coverUrl)
                    .crossfade(300)
                    .build(),
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (manga.genre.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text(
                        text = manga.genre,
                        color = Color.White,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = manga.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = manga.latestChapter,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── Real Book Grid ───────────────────────────────────────────────────────────

@Composable
fun RealBookGrid(
    items: List<Manga>,
    onNavigateToDetail: (String, String) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        val rows = items.chunked(3)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { manga ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToDetail(manga.detailUrl, manga.sourceName) }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(manga.coverUrl)
                                .crossfade(300)
                                .build(),
                            contentDescription = manga.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(8.dp))
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
                    }
                }
                // Fill remaining slots with empty space
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

// ─── Section Header ───────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, actionLabel: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Footer Notice ────────────────────────────────────────────────────────────

@Composable
fun FooterNoticeRow(onNavigateToNotice: () -> Unit = {}) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outline,
        thickness = 0.5.dp
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToNotice() }
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = "公告",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Text(
            text = "Drift 当前为开发早期版本，所有内容均为测试数据。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Drift 不存储任何版权内容，仅作聚合导航用途。\n© 2025 Drift Project · 开源 · 无广告",
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        fontSize = 10.sp,
        lineHeight = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    )
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, showSystemUi = true)
@Composable
fun MangaScreenDarkPreview() {
    DriftTheme(darkTheme = true) { MangaScreenSkeleton() }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, showSystemUi = true)
@Composable
fun MangaScreenLightPreview() {
    DriftTheme(darkTheme = false) { MangaScreenSkeleton() }
}
