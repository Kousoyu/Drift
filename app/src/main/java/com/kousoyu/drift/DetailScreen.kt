package com.kousoyu.drift

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kousoyu.drift.data.DetailState
import com.kousoyu.drift.data.DetailViewModel
import com.kousoyu.drift.data.MangaDetail
import com.kousoyu.drift.data.SourceManager
import com.kousoyu.drift.ui.theme.shimmerEffect
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    urlEncoded: String,
    sourceNameEncoded: String = "",
    onBack: () -> Unit,
    onChapterClick: (String, String) -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val decodedSourceName = remember(sourceNameEncoded) {
        if (sourceNameEncoded.isNotEmpty()) java.net.URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""
    }
    val coverHeaders = remember(decodedSourceName) {
        if (decodedSourceName.isNotEmpty()) SourceManager.getSourceByName(decodedSourceName).getHeaders()
        else SourceManager.currentSource.value.getHeaders()
    }

    LaunchedEffect(urlEncoded, sourceNameEncoded) {
        viewModel.loadDetail(urlEncoded, sourceNameEncoded)
    }

    val localManga by viewModel.localManga.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    val detail = (viewModel.state as? DetailState.Success)?.detail
                    if (detail != null) {
                        val isFav = localManga != null
                        FloatingActionButton(
                            onClick = { 
                                val decodedSource = if (sourceNameEncoded.isNotEmpty()) java.net.URLDecoder.decode(sourceNameEncoded, "UTF-8") else ""
                                viewModel.toggleFavorite(detail, decodedSource) 
                            },) {
                            Icon(
                                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) Color.Red else Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = viewModel.state) {
                is DetailState.Loading -> {
                    DetailSkeleton()
                }
                is DetailState.Error -> {
                    ErrorScreen(
                        message = state.message,
                        onRetry = { viewModel.loadDetail(urlEncoded) }
                    )
                }
                is DetailState.Success -> {
                    DetailContent(
                        detail = state.detail,
                        localManga = localManga,
                        onChapterClick = { url, name ->
                            com.kousoyu.drift.data.ChapterNavigation.chapters = state.detail.chapters
                            onChapterClick(url, name)
                        },
                        coverHeaders = coverHeaders,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun DetailContent(
    detail: MangaDetail,
    localManga: com.kousoyu.drift.data.local.MangaEntity?,
    onChapterClick: (String, String) -> Unit,
    coverHeaders: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expandedDesc by remember { mutableStateOf(false) }
    var isReversed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        // 1. Hero Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
        ) {
            // Blurred Background
            AsyncImage(
                model = ImageRequest.Builder(context).data(detail.coverUrl).crossfade(true).apply { coverHeaders.forEach { (k, v) -> addHeader(k, v) } }.build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
            )
            // Dark overlay
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)))

            // Foreground Cover
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .padding(top = 40.dp) // Leave space for TopAppBar
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(detail.coverUrl).crossfade(true).apply { coverHeaders.forEach { (k, v) -> addHeader(k, v) } }.build(),
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.align(Alignment.Bottom)) {
                    Text(
                        text = detail.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail.author,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = detail.status,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Description
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize()
                .clickable { expandedDesc = !expandedDesc }
        ) {
            Text(
                text = detail.description,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = if (expandedDesc) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!expandedDesc) {
                Text(
                    text = "展开全部 v",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "章节列表 (${detail.chapters.size})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            IconButton(onClick = { isReversed = !isReversed }) {
                Text(
                    text = if (isReversed) "↑" else "↓",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        
        if (localManga?.lastReadChapterUrl != null && localManga?.lastReadChapterName != null) {
            Button(
                onClick = { 
                    onChapterClick(localManga.lastReadChapterUrl, localManga.lastReadChapterName!!) 
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "继续阅读: ${localManga.lastReadChapterName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        
        val displayChapters = if (isReversed) detail.chapters.reversed() else detail.chapters
        GridChapters(
            chapters = displayChapters,
            lastReadUrl = localManga?.lastReadChapterUrl,
            onChapterClick = onChapterClick
        )
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}

// ─── 3-Column Chapter Grid ─────────────────────────────────────────────────────
//
// Uses List.chunked(3) + Row + weight(1f) to guarantee absolute equal-width
// columns regardless of chapter name length. Text is hard-truncated at 1 line
// so no item can grow taller than its neighbours.
//
@Composable
fun GridChapters(
    chapters: List<com.kousoyu.drift.data.MangaChapter>,
    lastReadUrl: String?,
    onChapterClick: (String, String) -> Unit
) {
    val rows = chapters.chunked(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { rowChapters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowChapters.forEach { chapter ->
                    val isLastRead = chapter.url == lastReadUrl
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isLastRead)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onChapterClick(chapter.url, chapter.name) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chapter.name,
                            color = if (isLastRead)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = if (isLastRead) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
                // Fill remaining slots in the last row to keep alignment
                repeat(3 - rowChapters.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── Detail Skeleton ──────────────────────────────────────────────────────────

@Composable
fun DetailSkeleton() {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Hero area skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .shimmerEffect()
        )

        // Cover + title block
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }
        }

        // Description skeleton
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (i == 2) 0.6f else 1f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // Chapter header skeleton
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .shimmerEffect()
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
        }

        // Chapter grid skeleton
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            repeat(4) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(3) { col ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .shimmerEffect()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

