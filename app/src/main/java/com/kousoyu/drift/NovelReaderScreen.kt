package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.data.NovelChapter
import com.kousoyu.drift.data.NovelDetailViewModel
import com.kousoyu.drift.data.NovelSourceManager
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Novel Reader Screen — tap-to-toggle UI.
 * - Persists reading progress to Room (bookshelf novels)
 * - Caches chapter content in memory (LRU 10)
 * - Preloads next chapter for zero-latency page turns
 * - Remembers scroll position per chapter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderScreen(
    chapterUrl: String,
    chapterName: String,
    allChapters: List<NovelChapter> = emptyList(),
    onBack: () -> Unit,
    onNavigateChapter: ((chapterUrl: String, chapterName: String) -> Unit)? = null
) {
    val source = NovelSourceManager.currentSource.collectAsState().value
    val detailVm: NovelDetailViewModel = viewModel()
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val decodedUrl = remember(chapterUrl) { java.net.URLDecoder.decode(chapterUrl, "UTF-8") }
    val decodedName = remember(chapterName) { java.net.URLDecoder.decode(chapterName, "UTF-8") }

    // Find current chapter index for prev/next
    val currentIndex = remember(decodedUrl, allChapters) {
        allChapters.indexOfFirst { it.url == decodedUrl }
    }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until allChapters.size - 1

    // ── Load chapter content ──
    LaunchedEffect(chapterUrl) {
        loading = true; error = null; content = null
        showMenu = false

        // Restore scroll position or reset
        val savedScroll = NovelScrollCache.get(decodedUrl)
        scrollState.scrollTo(savedScroll)

        // Cache hit → instant
        NovelContentCache.get(decodedUrl)?.let {
            content = it; loading = false
            return@LaunchedEffect
        }

        source.getChapterContent(decodedUrl)
            .onSuccess {
                content = it; loading = false
                NovelContentCache.put(decodedUrl, it)
            }
            .onFailure { error = it.message; loading = false }
    }

    // ── Save reading progress to Room (persistent) ──
    LaunchedEffect(decodedUrl) {
        // Extract novel URL from chapter URL for Room lookup
        val novelUrl = extractNovelBaseUrl(decodedUrl)
        if (novelUrl.isNotEmpty()) {
            detailVm.saveProgress(novelUrl, decodedName, decodedUrl)
        }
    }

    // ── Preload next chapter ──
    LaunchedEffect(decodedUrl) {
        if (hasNext) {
            val nextUrl = allChapters[currentIndex + 1].url
            if (NovelContentCache.get(nextUrl) == null) {
                source.getChapterContent(nextUrl).onSuccess { NovelContentCache.put(nextUrl, it) }
            }
        }
    }

    // ── Save scroll position on leave ──
    DisposableEffect(decodedUrl) {
        onDispose {
            NovelScrollCache.put(decodedUrl, scrollState.value)
        }
    }

    // ── Retry function ──
    fun retry() {
        coroutineScope.launch {
            loading = true; error = null
            source.getChapterContent(decodedUrl)
                .onSuccess {
                    content = it; loading = false
                    NovelContentCache.put(decodedUrl, it)
                }
                .onFailure { error = it.message; loading = false }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Main content area (tappable to toggle menu) ──
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载章节...", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { retry() }) { Text("重试") }
                            TextButton(onClick = onBack) { Text("返回") }
                        }
                    }
                }
            }

            content != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showMenu = !showMenu }
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .statusBarsPadding()
                ) {
                    // Chapter title
                    Text(
                        text = decodedName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Text(
                        text = content!!,
                        fontSize = 16.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        letterSpacing = 0.3.sp
                    )
                    Spacer(Modifier.height(80.dp))
                }
            }
        }

        // ── Top overlay: title + back (tap to show) ──
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = decodedName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(end = 16.dp)
                    )
                }
            }
        }

        // ── Bottom overlay: prev/next chapter (tap to show) ──
        AnimatedVisibility(
            visible = showMenu && allChapters.size > 1 && onNavigateChapter != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (hasPrev && onNavigateChapter != null) {
                                val prev = allChapters[currentIndex - 1]
                                onNavigateChapter(prev.url, prev.name)
                            }
                        },
                        enabled = hasPrev
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowLeft, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上一章", fontSize = 13.sp)
                    }

                    if (currentIndex >= 0) {
                        Text(
                            text = "${currentIndex + 1} / ${allChapters.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    TextButton(
                        onClick = {
                            if (hasNext && onNavigateChapter != null) {
                                val next = allChapters[currentIndex + 1]
                                onNavigateChapter(next.url, next.name)
                            }
                        },
                        enabled = hasNext
                    ) {
                        Text("下一章", fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowRight, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Chapter content cache — avoids re-fetching when navigating prev/next.
 * LRU-like: keeps last 10 chapters in memory.
 */
object NovelContentCache {
    private const val MAX = 10
    private val cache = LinkedHashMap<String, String>(MAX + 2, 0.75f, true)

    fun get(url: String): String? = cache[url]

    fun put(url: String, content: String) {
        cache[url] = content
        while (cache.size > MAX) {
            cache.remove(cache.keys.first())
        }
    }
}

/**
 * Scroll position cache per chapter — survives prev/next navigation.
 */
object NovelScrollCache {
    private const val MAX = 10
    private val cache = LinkedHashMap<String, Int>(MAX + 2, 0.75f, true)

    fun get(url: String): Int = cache[url] ?: 0
    fun put(url: String, position: Int) {
        cache[url] = position
        while (cache.size > MAX) cache.remove(cache.keys.first())
    }
}

/**
 * Extract the novel base URL from a chapter URL for Room progress lookup.
 * e.g. "https://www.linovelib.com/novel/8/25131.html" → "https://www.linovelib.com/novel/8.html"
 */
private fun extractNovelBaseUrl(chapterUrl: String): String {
    // Pattern: .../novel/{id}/{chapterId}.html → .../novel/{id}.html
    val regex = Regex("(https?://[^/]+/novel/\\d+)")
    val match = regex.find(chapterUrl)
    return match?.groupValues?.get(1)?.let { "$it.html" } ?: ""
}
