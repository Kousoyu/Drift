package com.kousoyu.drift

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.ui.theme.DriftTheme

// ─── Mock Notice Data ─────────────────────────────────────────────────────────

data class NoticeItem(
    val tag: String,
    val title: String,
    val date: String,
    val body: String
)

val mockNotices = listOf(
    NoticeItem(
        tag = "公告",
        title = "Drift 早期测试版开放说明",
        date = "2026.04.05",
        body = "感谢你使用 Drift！\n\n当前版本为内部早期测试版本，所有内容均为占位数据，核心网络解析引擎尚在开发中。\n\n如你遇到任何问题，欢迎反馈。"
    ),
    NoticeItem(
        tag = "声明",
        title = "关于版权与内容来源",
        date = "2026.04.05",
        body = "Drift 本身不存储、不托管任何受版权保护的漫画或小说内容。\n\nDrift 仅作为内容聚合导航，全部内容索引均来源于各原始平台的合法公开接口。"
    ),
    NoticeItem(
        tag = "路线图",
        title = "下一步开发计划",
        date = "2026.04.05",
        body = "✦ 接入真实漫画/小说图源解析引擎\n✦ 完善「我」页面（书架、主题设置）\n✦ 「追番」与「AI 助手」模块开发\n✦ 支持本地书架离线缓存\n\n更多更新请持续关注！"
    ),
    NoticeItem(
        tag = "关于",
        title = "Drift 是什么",
        date = "2026.04.05",
        body = "Drift（游离）是一款开源、无广告、极简主义的内容聚合阅读器。\n\n我们相信：好的软件应该是自由的、克制的、纯粹的。\n\n所有代码均开源，遵循 MIT 协议。"
    ),
)

// ─── Notice Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeScreen(onBack: () -> Unit) {
    // Track which item is expanded (-1 = none)
    var expandedIndex by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "公告事项",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            itemsIndexed(mockNotices) { index, notice ->
                NoticeAccordionItem(
                    notice = notice,
                    isExpanded = expandedIndex == index,
                    onToggle = {
                        expandedIndex = if (expandedIndex == index) -1 else index
                    }
                )
                // Ultra-thin divider — zero visual noise
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    thickness = 0.5.dp
                )
            }

            // Minimalist footer signature
            item {
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "Drift · 开源 · 无广告 · 自由",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

// ─── Accordion Item ───────────────────────────────────────────────────────────

@Composable
fun NoticeAccordionItem(
    notice: NoticeItem,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .animateContentSize(animationSpec = tween(durationMillis = 280))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Badge tag (公告 / 声明 / 路线图 etc.)
                Text(
                    text = notice.tag,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Main title
                Text(
                    text = notice.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(3.dp))
                // Date — muted
                Text(
                    text = notice.date,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chevron indicator
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                              else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        // Body text — only visible when expanded, slides in/out via animateContentSize
        if (isExpanded) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = notice.body,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, showSystemUi = true)
@Composable
fun NoticeScreenDarkPreview() {
    DriftTheme(darkTheme = true) {
        NoticeScreen(onBack = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, showSystemUi = true)
@Composable
fun NoticeScreenLightPreview() {
    DriftTheme(darkTheme = false) {
        NoticeScreen(onBack = {})
    }
}
