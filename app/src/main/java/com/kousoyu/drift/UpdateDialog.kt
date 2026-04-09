package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kousoyu.drift.data.UpdateManager

/**
 * Unified update dialog — handles discovery, download progress,
 * and error states all within a single dialog.
 *
 * States:
 *   Available   → show changelog + "立即更新" button
 *   Downloading → show progress bar + percentage inline
 *   Error       → show error message + "重试" button
 */
@Composable
fun UpdateDialog(
    info: UpdateManager.UpdateInfo,
    currentVersion: String,
    updateState: UpdateManager.UpdateState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDownloading = updateState is UpdateManager.UpdateState.Downloading
    val downloadProgress = if (isDownloading) {
        (updateState as UpdateManager.UpdateState.Downloading).progress
    } else 0

    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress / 100f,
        animationSpec = tween(300),
        label = "download_progress"
    )

    Dialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ─── Icon with gradient background ──────────────────────────
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDownloading) Icons.Filled.SystemUpdate
                                      else Icons.Filled.Rocket,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ─── Title ──────────────────────────────────────────────────
                Text(
                    text = if (isDownloading) "正在下载" else "发现新版本",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ─── Version badge ──────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "v$currentVersion",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "  →  ",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "v${info.versionName}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // ─── File size ──────────────────────────────────────────────
                if (info.fileSize > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val sizeMB = info.fileSize / (1024.0 * 1024.0)
                    Text(
                        text = "%.1f MB".format(sizeMB),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ─── Download progress (shown only during download) ─────────
                AnimatedVisibility(visible = isDownloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (downloadProgress > 0) "${downloadProgress}%" else "准备下载…",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ─── Error state ────────────────────────────────────────────
                if (updateState is UpdateManager.UpdateState.Error) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = (updateState as UpdateManager.UpdateState.Error).message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // ─── Changelog (hidden during download) ─────────────────────
                AnimatedVisibility(visible = !isDownloading && info.changelog.isNotBlank()) {
                    Column {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "更新日志",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = info.changelog,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // ─── Action buttons ─────────────────────────────────────────
                if (!isDownloading) {
                    val isError = updateState is UpdateManager.UpdateState.Error
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isError) "重试下载" else "立即更新",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "稍后提醒",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
