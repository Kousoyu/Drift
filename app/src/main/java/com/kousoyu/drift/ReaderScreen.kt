package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.imageLoader
import androidx.compose.ui.draw.clip
import com.kousoyu.drift.data.ReaderState
import com.kousoyu.drift.data.ReaderViewModel
import java.net.URLDecoder

// ─── Reader Screen ─────────────────────────────────────────────────────────────

@Composable
fun ReaderScreen(
    urlEncoded: String,
    mangaUrlEncoded: String = "",
    chapterNameEncoded: String = "",
    sourceNameEncoded: String = "",
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    var showMenu by remember { mutableStateOf(false) }
    val decodedChapterName = remember(chapterNameEncoded) {
        if (chapterNameEncoded.isNotEmpty()) URLDecoder.decode(chapterNameEncoded, "UTF-8") else "阅读中"
    }

    LaunchedEffect(urlEncoded) {
        viewModel.loadChapter(urlEncoded, mangaUrlEncoded, chapterNameEncoded, sourceNameEncoded)
    }

    val listState = rememberLazyListState()

    // ── Restore scroll position from saved page ──
    val successState = viewModel.state as? ReaderState.Success
    LaunchedEffect(successState?.initialPage) {
        val page = successState?.initialPage ?: 0
        if (page > 0 && successState != null) {
            listState.scrollToItem(page.coerceAtMost(successState.images.size - 1))
        }
    }

    // ── Track page changes (debounced save to Room) ──
    LaunchedEffect(listState.firstVisibleItemIndex) {
        viewModel.onPageChanged(listState.firstVisibleItemIndex)
    }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── Aggressive Prefetching Engine ──
    if (viewModel.state is ReaderState.Success) {
        val images = (viewModel.state as ReaderState.Success).images
        val headers = (viewModel.state as ReaderState.Success).headers
        val imageLoader = context.imageLoader
        
        LaunchedEffect(listState.firstVisibleItemIndex) {
            // Initiate prefetch window for the next 20 images
            val prefetchWindow = 20
            val startIdx = (listState.firstVisibleItemIndex + 2).coerceIn(0, images.size)
            val endIdx = (startIdx + prefetchWindow).coerceIn(0, images.size)
            
            for (i in startIdx until endIdx) {
                val cleanUrl = images[i].substringBefore("#")
                val request = ImageRequest.Builder(context)
                    .data(cleanUrl)
                    .memoryCacheKey(cleanUrl)
                    .diskCacheKey(cleanUrl)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        headers.forEach { (key, value) -> addHeader(key, value) }
                    }
                    .build()
                
                // Enqueue in the background
                imageLoader.enqueue(request)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (val state = viewModel.state) {

            // ── Initial chapter loading spinner ──────────────────────────────
            is ReaderState.Loading -> {
                ChapterLoadingSpinner()
            }

            // ── Error screen (full-chapter fetch failed) ─────────────────────
            is ReaderState.Error -> {
                ErrorScreen(
                    message = state.message,
                    onRetry = { viewModel.loadChapter(urlEncoded) }
                )
            }

            // ── Success: render pages ────────────────────────────────────────
            is ReaderState.Success -> {
                val context = LocalContext.current
                val images = state.images

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = images,
                        key = { index, _ -> index }
                    ) { index, imageUrl ->
                        MangaPage(
                            url = imageUrl,
                            pageNumber = index + 1,
                            totalPages = images.size,
                            context = context,
                            headers = state.headers,
                            onTap = { showMenu = !showMenu }
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }

        // ── Top overlay menu ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .statusBarsPadding()
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        decodedChapterName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // ── Always-visible page counter (subtle, bottom-right) ─────────────────
        if (viewModel.state is ReaderState.Success && !showMenu) {
            val images = (viewModel.state as ReaderState.Success).images
            val current = (listState.firstVisibleItemIndex + 1).coerceIn(1, images.size.coerceAtLeast(1))
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$current / ${images.size}",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // ── Bottom overlay: chapter nav + page counter ───────────────────────────
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .navigationBarsPadding()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev chapter
                IconButton(
                    onClick = { viewModel.navigateChapter(-1) },
                    enabled = viewModel.hasPrevChapter
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowLeft,
                        contentDescription = "上一章",
                        tint = if (viewModel.hasPrevChapter) Color.White else Color.White.copy(alpha = 0.2f)
                    )
                }

                // Page counter
                if (viewModel.state is ReaderState.Success) {
                    val images = (viewModel.state as ReaderState.Success).images
                    val current = (listState.firstVisibleItemIndex + 1).coerceIn(1, images.size.coerceAtLeast(1))
                    Text(
                        text = "$current / ${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Next chapter
                IconButton(
                    onClick = { viewModel.navigateChapter(1) },
                    enabled = viewModel.hasNextChapter
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowRight,
                        contentDescription = "下一章",
                        tint = if (viewModel.hasNextChapter) Color.White else Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

// ─── Single Manga Page ─────────────────────────────────────────────────────────
//
// Key design decisions:
//  • Uses SubcomposeAsyncImage so we can inject Loading/Error sub-composables.
//  • Telephoto Modifier.zoomable() enables extremely responsive pinch-to-zoom
//    and pan-bounds tracking without killing parent LazyColumn scrolls.
//  • Loading state has Modifier.heightIn(min = 600.dp) so the LazyColumn always
//    has something to measure — the user can scroll freely WITHOUT WAITING for the
//    image to download. The moment the image arrives it replaces the placeholder
//    at the correct intrinsic height.
//  • Error state provides a per-page retry tap, not a full-chapter re-fetch.
//  • ImageRequest uses ENABLED disk + network cache and deprioritises background
//    requests so on-screen images get bandwidth priority.
//

@Composable
private fun MangaPage(
    url: String,
    pageNumber: Int,
    totalPages: Int,
    context: android.content.Context,
    headers: Map<String, String>,
    onTap: () -> Unit = {}
) {
    val cleanUrl = url.substringBefore("#")
    var retryKey by remember { mutableIntStateOf(0) }

    val imageRequest = remember(cleanUrl, retryKey) {
        ImageRequest.Builder(context)
            .data(cleanUrl)
            .memoryCacheKey(cleanUrl)
            .diskCacheKey(cleanUrl)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }
            .crossfade(true)
            .build()
    }

    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = "第 $pageNumber 页",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { onTap() },
        loading = {
            PageLoadingPlaceholder(
                pageNumber = pageNumber,
                totalPages = totalPages
            )
        },
        error = {
            PageErrorPlaceholder(
                pageNumber = pageNumber,
                onRetry = { retryKey++ }
            )
        }
    )
}

// ─── Loading Placeholder ──────────────────────────────────────────────────────

@Composable
private fun PageLoadingPlaceholder(pageNumber: Int, totalPages: Int) {
    // Pulsing shimmer alpha
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // ← This is the critical fix: guarantee minimum height so the list
            //   has real pixel space to scroll into before the image loads.
            .heightIn(min = 600.dp)
            .background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        // Subtle shimmer strip at the centre
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(2.dp)
                .background(Color.White.copy(alpha = alpha))
        )

        // Minimal spinner — lets the user know "still working"
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 1.5.dp,
            color = Color.White.copy(alpha = 0.35f)
        )

        // Page counter hint in the corner
        Text(
            text = "$pageNumber / $totalPages",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.2f),
            fontWeight = FontWeight.Light
        )
    }
}

// ─── Per-page Error Placeholder ────────────────────────────────────────────────

@Composable
private fun PageErrorPlaceholder(pageNumber: Int, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
            .background(Color(0xFF111111))
            .clickable { onRetry() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "重试",
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "第 $pageNumber 页加载失败",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.25f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点击重试",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.15f)
            )
        }
    }
}

// ─── Initial Chapter Loading Screen ────────────────────────────────────────────

@Composable
private fun ChapterLoadingSpinner() {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val dot by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "dot_float"
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 1.5.dp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "获取章节" + ".".repeat(dot.toInt() + 1),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.3f),
                fontWeight = FontWeight.Light
            )
        }
    }
}
