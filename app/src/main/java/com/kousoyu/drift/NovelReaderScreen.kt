package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.data.NovelChapter
import com.kousoyu.drift.data.NovelSourceManager

/**
 * Novel Reader Screen — clean text reading experience with prev/next chapter.
 *
 * Design: minimal UI, comfortable reading, seamless chapter navigation.
 * The chapter list is passed in so we can navigate between chapters.
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
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    val decodedUrl = remember(chapterUrl) { java.net.URLDecoder.decode(chapterUrl, "UTF-8") }
    val decodedName = remember(chapterName) { java.net.URLDecoder.decode(chapterName, "UTF-8") }

    // Find current chapter index for prev/next
    val currentIndex = remember(decodedUrl, allChapters) {
        allChapters.indexOfFirst { it.url == decodedUrl }
    }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until allChapters.size - 1

    // Load chapter content
    LaunchedEffect(chapterUrl) {
        loading = true; error = null; content = null
        scrollState.scrollTo(0)
        source.getChapterContent(decodedUrl)
            .onSuccess { content = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    // Save reading position
    LaunchedEffect(decodedUrl) {
        NovelReadingProgress.save(decodedUrl, decodedName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = decodedName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                )
            )
        },
        // Bottom bar: prev/next chapter
        bottomBar = {
            if (allChapters.size > 1 && onNavigateChapter != null) {
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp
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
                                if (hasPrev) {
                                    val prev = allChapters[currentIndex - 1]
                                    onNavigateChapter(prev.url, prev.name)
                                }
                            },
                            enabled = hasPrev
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowLeft,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("上一章", fontSize = 13.sp)
                        }

                        // Chapter position indicator
                        if (currentIndex >= 0) {
                            Text(
                                text = "${currentIndex + 1} / ${allChapters.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }

                        TextButton(
                            onClick = {
                                if (hasNext) {
                                    val next = allChapters[currentIndex + 1]
                                    onNavigateChapter(next.url, next.name)
                                }
                            },
                            enabled = hasNext
                        ) {
                            Text("下一章", fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                        OutlinedButton(onClick = onBack) { Text("返回") }
                    }
                }
            }

            content != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = content!!,
                        fontSize = 16.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        letterSpacing = 0.3.sp
                    )
                    Spacer(Modifier.height(60.dp))
                }
            }
        }
    }
}

/**
 * Simple in-memory reading progress tracker.
 * Stores the last-read chapter URL and name per novel detail URL.
 */
object NovelReadingProgress {
    // detailUrl → (chapterUrl, chapterName)
    private val progress = mutableMapOf<String, Pair<String, String>>()

    fun save(chapterUrl: String, chapterName: String) {
        // Use the novel path as key: /novel/75/xxxx.html → /novel/75
        val novelKey = extractNovelKey(chapterUrl)
        if (novelKey.isNotEmpty()) {
            progress[novelKey] = chapterUrl to chapterName
        }
    }

    fun get(detailUrl: String): Pair<String, String>? {
        val key = extractNovelKey(detailUrl)
        return progress[key]
    }

    private fun extractNovelKey(url: String): String {
        // "/novel/75/xxxx.html" → "/novel/75"
        // "https://www.bilinovel.com/novel/75/xxxx.html" → "/novel/75"
        val path = url.substringAfter(".com", url) // remove domain if present
        val parts = path.split("/")
        val novelIdx = parts.indexOf("novel")
        return if (novelIdx >= 0 && novelIdx + 1 < parts.size) {
            "/novel/${parts[novelIdx + 1]}"
        } else ""
    }
}
