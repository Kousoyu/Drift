package com.kousoyu.drift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kousoyu.drift.data.*

/**
 * Novel Detail Screen — cover, info, sort toggle, continue reading, volume/chapter list.
 * Detail data is cached in memory → returning from chapter reader is instant.
 */

// Memory cache: detailUrl → NovelDetail (survives navigation)
private val detailCache = mutableMapOf<String, NovelDetail>()

// Sort order persisted per novel (survives navigation to reader and back)
private val sortOrderCache = mutableMapOf<String, Boolean>()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    detailUrl: String,
    onBack: () -> Unit,
    onChapterClick: (chapterUrl: String, chapterName: String, allChapters: List<NovelChapter>) -> Unit
) {
    val source = NovelSourceManager.currentSource.collectAsState().value
    var detail by remember { mutableStateOf<NovelDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isReversed by remember { mutableStateOf(sortOrderCache[detailUrl] ?: false) }

    // Load detail — uses cache if available (invalidate stale entries)
    LaunchedEffect(detailUrl) {
        val url = java.net.URLDecoder.decode(detailUrl, "UTF-8")
        detailCache[url]?.let { cached ->
            // Invalidate if old cache has no description or un-split volumes
            if (cached.description.isNotEmpty() && cached.volumes.size > 1) {
                detail = cached; loading = false; return@LaunchedEffect
            } else {
                detailCache.remove(url) // stale, re-fetch
            }
        }
        loading = true; error = null
        source.getNovelDetail(url)
            .onSuccess { detail = it; detailCache[url] = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    // All chapters flattened (for navigation)
    val allChapters = remember(detail, isReversed) {
        val flat = detail?.volumes?.flatMap { it.chapters } ?: emptyList()
        if (isReversed) flat.reversed() else flat
    }

    // Reading progress
    val decodedUrl = remember(detailUrl) { java.net.URLDecoder.decode(detailUrl, "UTF-8") }
    val lastRead = NovelReadingProgress.get(decodedUrl)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.title ?: "加载中...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
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
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载详情...", fontSize = 13.sp,
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
                        Text("加载失败", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(error ?: "", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp))
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onBack) { Text("返回") }
                    }
                }
            }

            detail != null -> {
                val d = detail!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    // ── Cover + Info Header ──
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (d.coverUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(d.coverUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = d.title,
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary.copy(0.3f),
                                                    MaterialTheme.colorScheme.tertiary.copy(0.2f)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📖", fontSize = 32.sp)
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = d.title,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (d.author.isNotEmpty()) {
                                    Text(
                                        text = d.author,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (d.status.isNotEmpty()) {
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = if ("连载" in d.status) MaterialTheme.colorScheme.primary.copy(0.1f)
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = d.status,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                color = if ("连载" in d.status) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${d.volumes.sumOf { it.chapters.size }} 章",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    // ── Description ──
                    if (d.description.isNotEmpty()) {
                        item {
                            Text(
                                text = d.description,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // ── Continue Reading Button ──
                    if (lastRead != null) {
                        item {
                            Button(
                                onClick = {
                                    val flat = d.volumes.flatMap { it.chapters }
                                    onChapterClick(lastRead.first, lastRead.second, flat)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("继续阅读 · ${lastRead.second}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // ── Divider + Sort Toggle ──
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "目录",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { isReversed = !isReversed; sortOrderCache[detailUrl] = isReversed }
                            ) {
                                Icon(
                                    Icons.Default.SwapVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isReversed) "倒序" else "正序",
                                    fontSize = 13.sp
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                        )
                    }

                    // ── Volumes & Chapters ──
                    val volumes = if (isReversed) d.volumes.reversed() else d.volumes
                    volumes.forEach { volume ->
                        item(key = "vol_${volume.name}_$isReversed") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Volume cover thumbnail
                                if (volume.coverUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(volume.coverUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = volume.name,
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(56.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Text(
                                    text = volume.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val chapters = if (isReversed) volume.chapters.reversed() else volume.chapters
                        items(chapters, key = { "${it.url}_$isReversed" }) { chapter ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (lastRead?.first == chapter.url)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                                onClick = {
                                    val flat = d.volumes.flatMap { it.chapters }
                                    onChapterClick(chapter.url, chapter.name, flat)
                                }
                            ) {
                                Text(
                                    text = chapter.name,
                                    fontSize = 14.sp,
                                    color = if (lastRead?.first == chapter.url)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
