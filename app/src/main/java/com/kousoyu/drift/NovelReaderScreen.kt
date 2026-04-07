package com.kousoyu.drift

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.data.NovelSourceManager

/**
 * Novel Reader Screen — clean text reading experience.
 *
 * Design: minimal UI, large text, comfortable reading.
 * No fancy decorations — the text IS the experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderScreen(
    chapterUrl: String,
    chapterName: String,
    onBack: () -> Unit
) {
    val source = NovelSourceManager.currentSource.collectAsState().value
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    // Load chapter content
    LaunchedEffect(chapterUrl) {
        loading = true
        error = null
        val url = java.net.URLDecoder.decode(chapterUrl, "UTF-8")
        source.getChapterContent(url)
            .onSuccess { content = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = java.net.URLDecoder.decode(chapterName, "UTF-8"),
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
