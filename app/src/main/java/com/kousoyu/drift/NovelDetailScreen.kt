package com.kousoyu.drift

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Novel Detail Screen — shows cover, info, and volume/chapter list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    detailUrl: String,
    onBack: () -> Unit,
    onChapterClick: (chapterUrl: String, chapterName: String) -> Unit
) {
    val source = NovelSourceManager.currentSource.collectAsState().value
    var detail by remember { mutableStateOf<NovelDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Load detail
    LaunchedEffect(detailUrl) {
        loading = true
        error = null
        val url = java.net.URLDecoder.decode(detailUrl, "UTF-8")
        source.getNovelDetail(url)
            .onSuccess { detail = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

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
                            // Cover
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

                            // Info
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

                    // ── Divider ──
                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                        )
                    }

                    // ── Volumes & Chapters ──
                    d.volumes.forEach { volume ->
                        // Volume header
                        item(key = "vol_${volume.name}") {
                            Text(
                                text = volume.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        // Chapters
                        items(volume.chapters, key = { it.url }) { chapter ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                onClick = { onChapterClick(chapter.url, chapter.name) }
                            ) {
                                Text(
                                    text = chapter.name,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
